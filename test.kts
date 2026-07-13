fun cleanText(input: String): String {
    var bodyClean = input.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").replace("\t", " ").replace("\u00A0", " ")
    bodyClean = bodyClean.replace(Regex(" +"), " ")
    return bodyClean
}

val test1 = "?덈뀞?섏꽭??n?ㅻ뒛 ?좎뵪媛\n醫뗫꽕??
val test2 = "?덈뀞?섏꽭?? ?ㅻ뒛   ?좎뵪媛 醫뗫꽕??
val test3 = "?쒓? 臾몄옄??u00A0?뚯뒪??

println("test1: '${cleanText(test1)}'")
println("test2: '${cleanText(test2)}'")
println("test3: '${cleanText(test3)}'")
