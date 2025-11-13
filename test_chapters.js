// Script para probar la extracción de capítulos en la consola del navegador
// Abre https://libribar.com/manga/la-leyenda-de-la-estrella-general/ y pega este script en la consola

console.log("=== INICIANDO TEST DE EXTRACCIÓN DE CAPÍTULOS ===");

// 1. Buscar todos los elementos li.wp-manga-chapter
const chapterLis = document.querySelectorAll("li.wp-manga-chapter");
console.log(`✓ Elementos <li class="wp-manga-chapter"> encontrados: ${chapterLis.length}`);

if (chapterLis.length === 0) {
    console.error("❌ NO SE ENCONTRARON ELEMENTOS <li> CON LA CLASE 'wp-manga-chapter'");
    console.log("Clases disponibles en los <li>:");
    document.querySelectorAll("li").forEach((li, i) => {
        if (i < 5) console.log(`  - ${li.className}`);
    });
} else {
    console.log("\n--- ANALIZANDO PRIMER CAPÍTULO ---");
    const firstLi = chapterLis[0];

    // Mostrar HTML del primer elemento
    console.log("HTML del primer <li>:");
    console.log(firstLi.outerHTML.substring(0, 500));

    // 2. Intentar buscar con el selector: span.chapter-link-container a
    console.log("\n--- PROBANDO SELECTOR: 'span.chapter-link-container a' ---");
    const linkWithContainer = firstLi.querySelector("span.chapter-link-container a");

    if (linkWithContainer) {
        console.log("✓ Enlace encontrado con 'span.chapter-link-container a'");
        console.log(`  - href: ${linkWithContainer.getAttribute('href')}`);
        console.log(`  - texto: "${linkWithContainer.textContent.trim()}"`);
        console.log(`  - clase: ${linkWithContainer.className}`);
    } else {
        console.error("❌ NO se encontró enlace con 'span.chapter-link-container a'");
    }

    // 3. Probar fallback: buscar cualquier <a>
    console.log("\n--- PROBANDO FALLBACK: cualquier 'a' ---");
    const anyLink = firstLi.querySelector("a");

    if (anyLink) {
        console.log("✓ Enlace encontrado con selector genérico 'a'");
        console.log(`  - href: ${anyLink.getAttribute('href')}`);
        console.log(`  - texto: "${anyLink.textContent.trim()}"`);
        console.log(`  - clase: ${anyLink.className}`);
    } else {
        console.error("❌ NO se encontró ningún enlace <a>");
    }

    // 4. Buscar todos los <a> para ver si hay múltiples
    console.log("\n--- TODOS LOS ENLACES EN EL PRIMER <li> ---");
    const allLinks = firstLi.querySelectorAll("a");
    console.log(`Total de enlaces <a>: ${allLinks.length}`);
    allLinks.forEach((link, index) => {
        console.log(`  Enlace ${index + 1}:`);
        console.log(`    - href: ${link.getAttribute('href')}`);
        console.log(`    - texto: "${link.textContent.trim()}"`);
        console.log(`    - clase: ${link.className}`);
        console.log(`    - parent: ${link.parentElement.className}`);
    });

    // 5. Extraer TODOS los capítulos
    console.log("\n=== EXTRAYENDO TODOS LOS CAPÍTULOS ===");
    let successCount = 0;
    let failCount = 0;
    const chapters = [];

    chapterLis.forEach((li, index) => {
        const link = li.querySelector("span.chapter-link-container a") || li.querySelector("a");

        if (link && link.getAttribute('href')) {
            const chapterData = {
                index: index,
                name: link.textContent.trim(),
                url: link.getAttribute('href'),
                fullUrl: link.href
            };
            chapters.push(chapterData);
            successCount++;

            // Mostrar solo los primeros 5 para no saturar la consola
            if (index < 5) {
                console.log(`✓ Capítulo ${index + 1}: ${chapterData.name} -> ${chapterData.url}`);
            }
        } else {
            failCount++;
            if (index < 5) {
                console.error(`❌ Capítulo ${index + 1}: NO SE ENCONTRÓ ENLACE`);
            }
        }
    });

    console.log(`\n--- RESUMEN ---`);
    console.log(`✓ Capítulos extraídos exitosamente: ${successCount}`);
    console.log(`❌ Capítulos que fallaron: ${failCount}`);
    console.log(`📊 Total: ${chapterLis.length}`);

    // 6. Verificar si hay fechas
    console.log("\n--- PROBANDO EXTRACCIÓN DE FECHAS ---");
    const firstChapterLi = chapterLis[0];
    const dateWithTitle = firstChapterLi.querySelector("span.chapter-release-date a[title]");
    const dateWithI = firstChapterLi.querySelector("span.chapter-release-date i");
    const dateSpan = firstChapterLi.querySelector("span.chapter-release-date");

    console.log("Fecha con a[title]:", dateWithTitle ? dateWithTitle.getAttribute('title') : "NO ENCONTRADO");
    console.log("Fecha con <i>:", dateWithI ? dateWithI.textContent : "NO ENCONTRADO");
    console.log("Fecha en span:", dateSpan ? dateSpan.textContent.trim() : "NO ENCONTRADO");

    // 7. Guardar en variable global para inspección
    window.extractedChapters = chapters;
    console.log("\n✓ Capítulos guardados en: window.extractedChapters");
    console.log("  Puedes inspeccionarlos escribiendo: extractedChapters");
}

console.log("\n=== TEST COMPLETADO ===");

