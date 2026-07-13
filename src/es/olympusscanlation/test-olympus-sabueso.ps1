$ErrorActionPreference = 'Stop'

function Assert-True($Condition, $Message) {
    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

$baseUrl = 'https://olympusxyz.com'
$panelUrl = 'https://panel.olympusxyz.com'
$mangaId = 743
$slug = '20-225-2sabueso13424'
$expectedTitle = 'La venganza del sabueso de sangre de hierro'

Write-Host "Testing Olympus manga ${mangaId}: $expectedTitle"

$detailsUrl = "$baseUrl/api/series/$slug`?type=comic"
$details = Invoke-RestMethod -Uri $detailsUrl -Headers @{ Referer = "$baseUrl/" }

Assert-True ($details.data.id -eq $mangaId) "details id should be $mangaId"
Assert-True ($details.data.name -eq $expectedTitle) "details title should be '$expectedTitle'"
Assert-True ([string]::IsNullOrWhiteSpace($details.data.cover) -eq $false) 'details cover should not be empty'
Assert-True ($details.data.cover -match '^https?://') 'details cover should be an absolute URL'

Write-Host "Details OK: $($details.data.name) — cover: $($details.data.cover)"

$allChapters = @()
$page = 1
$lastPage = 1

do {
    $chaptersUrl = "$panelUrl/api/series/$slug/chapters?page=$page&direction=desc&type=comic"
    $payload = Invoke-RestMethod -Uri $chaptersUrl -Headers @{ Referer = "$baseUrl/" }
    Assert-True ($payload.data.Count -gt 0) "chapter page $page should not be empty"

    $allChapters += $payload.data
    $lastPage = [int]$payload.meta.last_page
    $page++
} while ($page -le $lastPage)

Assert-True ($allChapters.Count -ge 100) 'expected a large chapter list for Sabueso'
Assert-True (($allChapters | Where-Object { $_.name -eq '170' -and $_.id -eq 130304 }).Count -eq 1) 'expected latest known chapter 170 with backend id 130304'
Assert-True (($allChapters | Where-Object { $_.name -eq '145.5' }).Count -ge 1) 'expected decimal chapter 145.5 to verify stable chapter-number identity'

Write-Host "Chapter list OK: $($allChapters.Count) chapters across $lastPage pages"

$chaptersToCheck = @(
    ($allChapters | Where-Object { $_.name -eq '170' } | Select-Object -First 1),
    ($allChapters | Where-Object { $_.name -eq '145.5' } | Select-Object -First 1),
    ($allChapters | Select-Object -Last 1)
) | Where-Object { $null -ne $_ }

foreach ($chapter in $chaptersToCheck) {
    $pageUrl = "$baseUrl/api/capitulo/comic-$slug/$($chapter.id)"
    $pagePayload = Invoke-RestMethod -Uri $pageUrl -Headers @{ Referer = "$baseUrl/" }

    Assert-True ($pagePayload.series_id -eq $mangaId) "chapter $($chapter.name) should belong to series $mangaId"
    Assert-True ($pagePayload.chapter.name -eq $chapter.name) "chapter endpoint should return chapter name $($chapter.name)"
    Assert-True ($pagePayload.chapter.pages.Count -gt 0) "chapter $($chapter.name) should have pages"
    Assert-True ($pagePayload.chapter.pages[0] -match '^https?://') "chapter $($chapter.name) first page should be an absolute URL"

    Write-Host "Chapter OK: $($chapter.name) -> backend id $($chapter.id), pages: $($pagePayload.chapter.pages.Count)"
}

Write-Host 'Olympus Sabueso integration test passed.'
