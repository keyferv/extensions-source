# Test de integración para JeazScans
# Ejecutar: pwsh -File src/es/jeazscans/test-integration.ps1

$baseUrl = "https://lectorhub.j5z.xyz"
$passed = 0
$failed = 0

function Assert($condition, $message) {
    if ($condition) {
        Write-Host "  ✓ $message" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "  ✗ $message" -ForegroundColor Red
        $script:failed++
    }
}

Write-Host "`n=== JEAZSCANS - TESTS DE INTEGRACIÓN ===`n" -ForegroundColor Cyan

# Test 1: Sitio accesible
Write-Host "TEST 1: Verificar sitio accesible" -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri $baseUrl -UseBasicParsing -TimeoutSec 15
    Assert ($response.StatusCode -eq 200) "Sitio responde HTTP 200"
    Assert ($response.Content.Length -gt 0) "Contenido no vacío"
    
    $html = $response.Content
    $hasMangaRefs = $html -match "manhua|manhwa|manga|capítulo|lector"
    Assert $hasMangaRefs "HTML contiene referencias a contenido manga/manhua"
    Write-Host "  Título detectado en HTML" -ForegroundColor Gray
} catch {
    Assert $false "Error al conectar: $($_.Exception.Message)"
}

# Test 2: Verificar estructura Madara
Write-Host "`nTEST 2: Verificar estructura de Madara" -ForegroundColor Yellow
try {
    $doc = Invoke-WebRequest -Uri $baseUrl -UseBasicParsing -TimeoutSec 15
    $html = $doc.Content
    $hasMadara = $html -match "wp-manga|madara|page-item-detail|manga-item|manhua|manhwa"
    Assert $hasMadara "HTML contiene selectores típicos de contenido"
    
    $hasChapters = $html -match "chapter|capítulo|wp-manga-chapter"
    Assert $hasChapters "HTML contiene referencias a capítulos"
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Test 3: Verificar que la URL configurada coincide
Write-Host "`nTEST 3: Verificar URL configurada" -ForegroundColor Yellow
try {
    $buildGradle = Get-Content "src/es/jeazscans/build.gradle" -Raw
    $hasCorrectUrl = $buildGradle -match "lectorhub.j5z.xyz"
    Assert $hasCorrectUrl "build.gradle contiene URL correcta (lectorhub.j5z.xyz)"
    
    $ktFile = Get-Content "src/es/jeazscans/src/eu/kanade/tachiyomi/extension/es/jeazscans/JeazScans.kt" -Raw
    $hasUrlInKt = $ktFile -match "lectorhub.j5z.xyz"
    Assert $hasUrlInKt "JeazScans.kt contiene URL correcta"
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Resumen
Write-Host "`n=== RESUMEN ===" -ForegroundColor Cyan
Write-Host "Pasados: $passed" -ForegroundColor Green
Write-Host "Fallidos: $failed" -ForegroundColor Red

if ($failed -gt 0) {
    exit 1
}
Write-Host "`n✅ TODOS LOS TESTS PASARON`n" -ForegroundColor Green
