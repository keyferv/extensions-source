# Test de integración para OlympusScanlation
# Ejecutar: pwsh -File src/es/olympusscanlation/test-integration.ps1

$baseUrl = "https://olympusbiblioteca.com"
$dashboardUrl = "https://dashboard.olympusbiblioteca.com"
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

function Fetch-Json($url) {
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 15 -Headers @{
        "Accept" = "application/json"
        "User-Agent" = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
    return $response.Content | ConvertFrom-Json
}

Write-Host "`n=== OLYMPUS SCANLATION - TESTS DE INTEGRACIÓN ===`n" -ForegroundColor Cyan

# Test 1: API /series/list
Write-Host "TEST 1: API /series/list" -ForegroundColor Yellow
try {
    $data = Fetch-Json "$baseUrl/api/series/list"
    Assert ($data.data -ne $null) "Respuesta contiene campo 'data'"
    Assert ($data.data.Count -gt 0) "Lista contiene $($data.data.Count) series"
    
    $first = $data.data[0]
    Assert ($first.id -ne $null) "Primera serie tiene 'id'"
    Assert ($first.name -ne $null -and $first.name -ne "") "Primera serie tiene 'name'"
    Assert ($first.slug -ne $null -and $first.slug -ne "") "Primera serie tiene 'slug'"
    Write-Host "  Primera serie: id=$($first.id), name='$($first.name)'" -ForegroundColor Gray
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Test 2: API /series/{slug}
Write-Host "`nTEST 2: API /series/{slug}" -ForegroundColor Yellow
try {
    $list = Fetch-Json "$baseUrl/api/series/list"
    $slug = $list.data[0].slug
    Write-Host "  Probando slug: $slug" -ForegroundColor Gray
    
    $detail = Fetch-Json "$baseUrl/api/series/$slug`?type=comic"
    Assert ($detail.data -ne $null) "Detalle contiene 'data'"
    Assert ($detail.data.id -ne $null) "Detalle tiene 'id'"
    Assert ($detail.data.name -ne $null) "Detalle tiene 'name'"
    Assert ($detail.data.slug -eq $slug) "Slug coincide"
    Write-Host "  Nombre: $($detail.data.name)" -ForegroundColor Gray
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Test 3: API /series/{slug}/chapters
Write-Host "`nTEST 3: API /series/{slug}/chapters" -ForegroundColor Yellow
try {
    $list = Fetch-Json "$baseUrl/api/series/list"
    $slug = $list.data[0].slug
    Write-Host "  Probando capítulos para: $slug" -ForegroundColor Gray
    
    $chapters = Fetch-Json "$dashboardUrl/api/series/$slug/chapters?page=1"
    Assert ($chapters.data -ne $null) "Capítulos contienen 'data'"
    $count = $chapters.data.Count
    Assert ($count -ge 0) "Se obtuvieron $count capítulos"
    if ($count -gt 0) {
        Assert ($chapters.data[0].id -ne $null) "Primer capítulo tiene 'id'"
        Assert ($chapters.data[0].name -ne $null) "Primer capítulo tiene 'name'"
        Write-Host "  Primer capítulo: $($chapters.data[0].name)" -ForegroundColor Gray
    }
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Test 4: API /series (estructura de búsqueda)
Write-Host "`nTEST 4: API /series (estructura paginada)" -ForegroundColor Yellow
try {
    $search = Fetch-Json "$baseUrl/api/series?page=1&type=comic"
    Assert ($search.data -ne $null) "Respuesta contiene 'data'"
    Assert ($search.data.series -ne $null) "Contiene 'data.series'"
    Assert ($search.data.series.data -ne $null) "Contiene 'data.series.data'"
    $count = $search.data.series.data.Count
    Assert ($count -gt 0) "series.data contiene $count elementos"
    
    $first = $search.data.series.data[0]
    Assert ($first.name -ne $null) "Elemento tiene 'name'"
    Assert ($first.slug -ne $null) "Elemento tiene 'slug'"
    Write-Host "  Primero: '$($first.name)'" -ForegroundColor Gray
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Test 5: Resolución de slug por ID
Write-Host "`nTEST 5: Resolución de slug por ID" -ForegroundColor Yellow
try {
    $list = Fetch-Json "$baseUrl/api/series/list"
    $target = $list.data | Where-Object { $_.id -eq 1226 } | Select-Object -First 1
    if (-not $target) {
        $target = $list.data[0]
    }
    Write-Host "  Objetivo: id=$($target.id), name='$($target.name)', slug='$($target.slug)'" -ForegroundColor Gray
    
    # Verificar que el slug resuelve capítulos
    $chapters = Fetch-Json "$dashboardUrl/api/series/$($target.slug)/chapters?page=1"
    Assert ($chapters.data -ne $null) "Slug resuelve a capítulos"
    Write-Host "  Capítulos: $($chapters.data.Count)" -ForegroundColor Gray
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Test 6: Manga específico conocido (Guia de supervivencia)
Write-Host "`nTEST 6: Manga específico - Guia de supervivencia" -ForegroundColor Yellow
try {
    $list = Fetch-Json "$baseUrl/api/series/list"
    $guia = $list.data | Where-Object { $_.name -like "*supervivencia*" } | Select-Object -First 1
    if ($guia) {
        Write-Host "  Encontrado: $($guia.name) slug=$($guia.slug)" -ForegroundColor Gray
        $chapters = Fetch-Json "$dashboardUrl/api/series/$($guia.slug)/chapters?page=1"
        Assert ($chapters.data.Count -gt 0) "Manga tiene capítulos"
        Write-Host "  Capítulos: $($chapters.data.Count)" -ForegroundColor Gray
    } else {
        Assert $false "No se encontró el manga 'Guia de supervivencia'"
    }
} catch {
    Assert $false "Error: $($_.Exception.Message)"
}

# Resumen
Write-Host "`n=== RESUMEN ===" -ForegroundColor Cyan
Write-Host "Pasados: $passed" -ForegroundColor Green
Write-Host "Fallidos: $failed" -ForegroundColor Red

if ($failed -gt 0) {
    Write-Host "`n❌ ALGUNOS TESTS FALLARON`n" -ForegroundColor Red
    exit 1
}
Write-Host "`n✅ TODOS LOS TESTS PASARON`n" -ForegroundColor Green
