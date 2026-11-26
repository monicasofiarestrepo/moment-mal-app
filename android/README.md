# Moment mal — Android

Kotlin + Jetpack Compose · minSdk 29 · target 35. Spec completa: [../SPEC.md](../SPEC.md).

## Estructura
```
app/src/main/java/app/momentmal/
 ├─ content/   Models.kt (esquema JSON), Catalog.kt (notas → tarjetas), Presenter.kt (frente/reverso + opción múltiple)
 ├─ srs/       Fsrs.kt (FSRS-5 + pasos de aprendizaje) y niveles Mastery
 ├─ session/   SessionEngine.kt (selección: repasos → nuevas → quiz; pesos 70/15/15; "Nochmal" al final)
 ├─ data/      Room: card_state, review_log, session
 ├─ blocker/   BlockerService (apps + URLs en navegadores), UsageTracker (tiempo), Rules (límites/dominios),
 │            ChallengeActivity (reto encima de la app), BlockerPrefs (apps, webs, límites, gracia, saltos, pausas)
 ├─ sync/      SyncManager (GitHub, token cifrado en Keystore) · content/ContentSync (descarga + validación + instalación atómica)
 ├─ stats/     cálculos de racha y tiempo ahorrado, Vergleichswert
 ├─ backup/    exportar/importar JSON, backup semanal en Download/MomentMal
 ├─ work/      WorkManager: sync diario, backup semanal
 └─ ui/        tema oscuro, Start, Study, Statistik, Einstellungen (Apps & Websites, Limits, Pausetag, Inhalte, Backup)
```
El contenido se copia de `../content` (manifest + decks) a los assets en cada build.

## Probar
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest      # tests del núcleo (contenido, FSRS, sesiones)
./gradlew :app:assembleRelease        # APK optimizado (~3.5 MB) en app/build/outputs/apk/release/
```
O abre esta carpeta (`blocker/android`) en Android Studio y dale ▶.

## Instalar en el Samsung A55
1. Ajustes → Acerca del teléfono → Información de software → toca **Número de compilación** 7 veces.
2. Ajustes → Opciones de desarrollador → activa **Depuración USB** (o **Depuración inalámbrica**).
3. Conecta el teléfono y acepta la huella del Mac.
4. `adb install -r app/build/outputs/apk/release/app-release.apk` (o ▶ en Android Studio con el A55 seleccionado).
5. Abre Moment mal → ⚙ (arriba a la derecha) y sigue los 4 pasos de „Schutz einrichten“. El paso 1 (eingeschränkte Einstellungen) es obligatorio en Android 14+ para apps instaladas fuera del Play Store, si no, Samsung no deja activar la Bedienungshilfe.
