// Para ejecutar este código:
// 1. Copia todo este contenido
// 2. Ve a https://play.kotlinlang.org/
// 3. Pega el código
// 4. Click en "Run" (▶)
// 5. Revisa la consola de output

// NOTA: En Kotlin Playground, necesitas agregar la dependencia de Jsoup manualmente
// O simplemente ejecuta esto en tu proyecto donde ya tienes Jsoup

fun main() {
    // Simulación del código sin Jsoup (para que puedas ver la lógica)
    println("=== SIMULACIÓN DE EXTRACCIÓN DE CAPÍTULOS ===")
    println()
    println("Este script simula lo que hace el código de BarManga:")
    println()

    // Simular los selectores
    val chapterListSelector = "li.wp-manga-chapter"
    val chapterUrlSelector = "span.chapter-link-container a"
    val dateSelector1 = "span.chapter-release-date a[title]"
    val dateSelector2 = "span.chapter-release-date i"
    val dateSelector3 = "span.chapter-release-date"

    println("1. BUSCAR CAPÍTULOS:")
    println("   Selector: '$chapterListSelector'")
    println("   ✓ Debería encontrar 311 elementos <li>")
    println()

    println("2. EXTRAER ENLACES:")
    println("   Selector principal: '$chapterUrlSelector'")
    println("   Fallback: select('a').firstOrNull { !it.hasClass('c-new-tag') }")
    println("   ✓ Cada <li> tiene 2 enlaces <a>:")
    println("     - Enlace 1: dentro de span.chapter-link-container (✓ CORRECTO)")
    println("     - Enlace 2: con clase c-new-tag (❌ VACÍO)")
    println()

    println("3. PROCESAR CADA CAPÍTULO:")
    println("   Para cada <li>:")
    println("     1. Buscar: span.chapter-link-container a")
    println("     2. Si no existe, buscar: primer <a> sin clase c-new-tag")
    println("     3. Extraer href y texto")
    println("     4. Construir URL: href + '?style=list'")
    println()

    println("4. EXTRAER FECHAS:")
    println("   Prioridad:")
    println("   1. '$dateSelector1' -> attr('title')")
    println("   2. '$dateSelector2' -> text()")
    println("   3. '$dateSelector3' -> text()")
    println()

    println("5. RESULTADO ESPERADO:")
    println("   ✅ 311 capítulos extraídos")
    println("   ✅ Cada uno con nombre, URL y fecha")
    println()

    println("=== PARA PROBAR CON DATOS REALES ===")
    println("Ejecuta este comando en tu proyecto:")
    println()
    println("  gradlew.bat :src:es:barmanga:assembleDebug")
    println()
    println("O ejecuta el script test_chapters.kts con:")
    println()
    println("  kotlin test_chapters.kts")
    println()
}

main()

