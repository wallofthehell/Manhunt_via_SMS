package com.example.locationtracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class LocationData(
    val id: String,
    val address: String,
    val date: Long,
    val body: String
)

data class LatLngData(val lat: Double, val lng: Double, val address: String)

val GovBlue = Color(0xFF004EA2)
val GovRed = Color(0xFFC82025)
val LightGray = Color(0xFFF5F5F5)

val GovColorScheme = lightColorScheme(
    primary = GovBlue,
    onPrimary = Color.White,
    primaryContainer = GovBlue,
    onPrimaryContainer = Color.White,
    secondary = GovRed,
    onSecondary = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    background = LightGray,
    onBackground = Color.Black
)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.READ_SMS] ?: false
        if (smsGranted) {
            Toast.makeText(this, "권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "SMS 권한이 거부되어 기능을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val requiredPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
        if (requiredPermissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(requiredPermissions)
        }

        setContent {
            MaterialTheme(colorScheme = GovColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LocationTrackerScreen(::extractAddressesFromSms)
                }
            }
        }
    }

    private suspend fun extractAddressesFromSms(targetNumber: String, startTime: Long, endTime: Long): List<LocationData> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<LocationData>()
        val addressRegex = Regex("([가-힣\\s]+(?:시|도|군|구|읍|면|동|리|로|길)\\s*(?:산\\s*)?\\d+(?:-\\d+)?(?:번지)?(?:\\s*\\d+동)?)")
        
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY
        )
        
        val selection = "${Telephony.Sms.ADDRESS} LIKE ? AND ${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?"
        val selectionArgs = arrayOf("%$targetNumber%", startTime.toString(), endTime.toString())
        
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
            val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
            
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIdx) ?: ""
                val match = addressRegex.find(body)
                if (match != null) {
                    resultList.add(
                        LocationData(
                            id = cursor.getString(idIdx),
                            address = match.value.trim(),
                            date = cursor.getLong(dateIdx),
                            body = body
                        )
                    )
                }
            }
        }
        
        return@withContext resultList
    }
}



enum class SearchMode { ALL_TIME, RECENT_24H, DATE_RANGE }

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationTrackerScreen(
    extractSmsFunc: suspend (String, Long, Long) -> List<LocationData>
) {
    var targetNumber by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(SearchMode.ALL_TIME) }
    
    var showDateRangePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()
    
    var locationList by remember { mutableStateOf<List<LocationData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val displayFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val simpleDateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val number = cursor.getString(0) ?: ""
                        targetNumber = number.replace("-", "").replace(" ", "")
                    }
                }
            }
        }
    }

    val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.bufferedWriter().use { writer ->
                            writer.write('\uFEFF'.toString())
                            writer.write("날짜,원본메시지,추출된주소\n")
                            locationList.forEach { loc ->
                                val dateStr = displayFormatter.format(Date(loc.date))
                                val body = loc.body.replace("\"", "\"\"").replace("\n", " ")
                                val addr = loc.address.replace("\"", "\"\"")
                                writer.write("\"$dateStr\",\"$body\",\"$addr\"\n")
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "CSV 파일이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (showDateRangePicker) {
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(onClick = { showDateRangePicker = false }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) { Text("취소") }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.weight(1f)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("어딨냐(통신영장)") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = targetNumber,
                    onValueChange = { targetNumber = it },
                    label = { Text("조회할 연락처 (예: 01012345678)") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        contactPicker.launch(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GovRed)
                ) {
                    Text("주소록")
                }
            }
            
            Text("조회 기간 설정", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = searchMode == SearchMode.ALL_TIME, onClick = { searchMode = SearchMode.ALL_TIME })
                Text("전체 기간 (기본)")
                Spacer(modifier = Modifier.width(8.dp))
                RadioButton(selected = searchMode == SearchMode.RECENT_24H, onClick = { searchMode = SearchMode.RECENT_24H })
                Text("최근 24시간")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = searchMode == SearchMode.DATE_RANGE, onClick = { searchMode = SearchMode.DATE_RANGE })
                Text("기간 지정 (달력)")
                Spacer(modifier = Modifier.width(8.dp))
                if (searchMode == SearchMode.DATE_RANGE) {
                    val startStr = dateRangePickerState.selectedStartDateMillis?.let { simpleDateFormatter.format(Date(it)) } ?: "시작일"
                    val endStr = dateRangePickerState.selectedEndDateMillis?.let { simpleDateFormatter.format(Date(it)) } ?: "종료일"
                    OutlinedButton(onClick = { showDateRangePicker = true }) {
                        Text("$startStr ~ $endStr")
                    }
                }
            }
            
            Button(
                onClick = {
                    if (targetNumber.isBlank()) {
                        Toast.makeText(context, "연락처를 입력해주세요.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            val startTime: Long
                            val endTime: Long
                            
                            when (searchMode) {
                                SearchMode.ALL_TIME -> {
                                    startTime = 0L
                                    endTime = Long.MAX_VALUE
                                }
                                SearchMode.RECENT_24H -> {
                                    endTime = System.currentTimeMillis()
                                    startTime = endTime - (24L * 60L * 60L * 1000L)
                                }
                                SearchMode.DATE_RANGE -> {
                                    val start = dateRangePickerState.selectedStartDateMillis
                                    val end = dateRangePickerState.selectedEndDateMillis
                                    if (start != null && end != null) {
                                        startTime = start
                                        endTime = end + (24L * 60L * 60L * 1000L) - 1L
                                    } else {
                                        Toast.makeText(context, "달력에서 시작일과 종료일을 모두 선택해주세요.", Toast.LENGTH_SHORT).show()
                                        isLoading = false
                                        return@launch
                                    }
                                }
                            }
                            
                            locationList = extractSmsFunc(targetNumber, startTime, endTime)
                            if (locationList.isEmpty()) {
                                Toast.makeText(context, "해당 기간에 추출된 주소가 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GovBlue)
            ) {
                Text("주소 추출 및 분석")
            }
            
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            
            if (locationList.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            csvExporter.launch("location_history.csv")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("CSV 파일 저장")
                    }
                }
                
                Text("추출된 목록 (${locationList.size}건) - 터치 시 구글 지도 실행", style = MaterialTheme.typography.titleMedium, color = GovBlue)
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(locationList) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val uri = Uri.parse("https://www.google.co.kr/maps/search/?api=1&query=${Uri.encode(item.address)}")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(32.dp)) {
                                Text(text = item.address, style = MaterialTheme.typography.titleMedium, color = GovBlue)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = displayFormatter.format(Date(item.date)), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = item.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                            }
                        }
                    }
                }
            }
        }
    }
}
