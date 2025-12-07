@echo off
cd /d "C:\Users\Usuario\StudioProjects\extensions-source\src\es\barmanga\src\eu\kanade\tachiyomi\extension\es\barmanga"

echo Compilando TestPages.kt...
kotlinc TestPages.kt -include-runtime -d TestPages.jar -cp "%USERPROFILE%\.m2\repository\org\jsoup\jsoup\1.15.3\jsoup-1.15.3.jar"

if errorlevel 1 (
    echo.
    echo ERROR: La compilacion fallo
    pause
    exit /b 1
)

echo.
echo Ejecutando test...
echo.
java -cp "TestPages.jar;%USERPROFILE%\.m2\repository\org\jsoup\jsoup\1.15.3\jsoup-1.15.3.jar" eu.kanade.tachiyomi.extension.es.barmanga.TestPagesKt

echo.
pause

