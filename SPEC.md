# Moment mal — Especificación v0.1

> App personal de Android (Samsung A55 5G) que intercepta apps y sitios distractores y exige completar retos de estudio (flashcards + opción múltiple) con repetición espaciada a largo plazo.
>
> Interfaz en alemán · fondo oscuro · uso exclusivo personal · instalación por APK (sin Play Store).

---

## 1. Plataforma y stack

| Aspecto | Decisión |
|---|---|
| Lenguaje / UI | Kotlin + Jetpack Compose (Material 3, tema propio) |
| Android | minSdk 29, targetSdk 35 (el A55 corre One UI 6/7 = Android 14/15) |
| Persistencia | Room (SQLite) para progreso y logs; DataStore para ajustes |
| Algoritmo | FSRS (versión 5/6, el de Anki moderno) implementado en Kotlin |
| Contenido | JSON por tema en un repo **privado** de GitHub, sincronizado; copia inicial empaquetada en `assets/` |
| Distribución | APK firmado, instalado por sideload |

### Permisos necesarios
- `AccessibilityService`: detectar la app en primer plano y la URL en navegadores. Es el núcleo, igual que en one sec.
- `PACKAGE_USAGE_STATS`: medir tiempo de uso por app y calcular la línea base de “tiempo ahorrado”.
- `SYSTEM_ALERT_WINDOW`: respaldo para mostrar el reto encima de la app.
- `POST_NOTIFICATIONS`: aviso persistente si el servicio se desactiva.
- `INTERNET`: sincronizar el contenido.
- Device Admin (opcional, “modo estricto”): añade fricción para desinstalar.

### Particularidades de Samsung / Android 14+
- Las apps instaladas por sideload necesitan **“Eingeschränkte Einstellungen zulassen”** (Ajustes → Apps → Moment mal → ⋮) antes de poder activar Accesibilidad. El onboarding guía este paso.
- Batería → **“Nie im Standby”** (*Never sleeping apps*) y desactivar “Pausieren, wenn ungenutzt”. Si no, One UI mata el servicio.
- Un watchdog (WorkManager cada 15 min) comprueba que el servicio sigue vivo. Si se cayó, muestra una notificación.

---

## 2. Módulos

```
app/
 ├─ blocker/        AccessibilityService, detección de apps/URLs, reglas de acceso
 ├─ usage/          tiempo por app y total, límites, reinicio a medianoche
 ├─ session/        motor de sesiones (selección de tarjetas, mezcla, MCQ)
 ├─ srs/            FSRS + niveles de dominio
 ├─ content/        parser/validador JSON, sync con GitHub, versiones
 ├─ data/           Room (entidades, DAOs), DataStore
 ├─ stats/          racha, tiempo ahorrado, dominio por tema
 ├─ backup/         exportar / importar progreso
 └─ ui/             pantallas Compose, tema, strings (de)
```

---

## 3. Intercepción (estilo one sec)

1. El `AccessibilityService` escucha `TYPE_WINDOW_STATE_CHANGED`.
   - Si el paquete en primer plano está en la lista bloqueada → regla de acceso.
   - Si es un navegador (Chrome, Samsung Internet, Firefox, Brave, Edge…), lee la barra de URL. Si el dominio coincide con la lista → regla de acceso.
2. **Regla de acceso** (en orden):
   1. ¿Día en pausa? → deja pasar.
   2. ¿Dentro del **periodo de gracia** (20 min) de esa app/dominio? → deja pasar.
   3. Calcula N preguntas (sección 5) → lanza `ChallengeActivity` encima, sin animación.
3. Al terminar el reto, se vuelve a la app de origen y arranca la gracia de 20 min.
4. Si sales del reto con Home o Atrás, no entras. La próxima vez que abras la app, el reto empieza de cero.

**Lista inicial de apps:** Instagram, TikTok, Pinterest, Facebook, YouTube, X, Reddit.
**Lista inicial de dominios:** instagram.com, tiktok.com, pinterest.com, facebook.com, youtube.com, x.com, twitter.com, reddit.com.

Ambas listas se editan **desde la app** (Einstellungen → Apps & Websites): un selector con todas las apps instaladas (icono + nombre) y un campo para dominios. Navegador principal: **Chrome** (`com.android.chrome`); los demás navegadores quedan soportados pero no son prioritarios.

**Límites por defecto** (editables): 30 min por app, 90 min en total.
**Gracia:** por app.

---

## 3b. Notiz y regreso al link

- **Notiz:** en la primera pregunta hay un botón discreto „Notiz“ (arriba a la izquierda) que abre un campo: *„Was wolltest du dort machen?“*. Enter lo cierra. Al terminar el reto, la nota aparece en la pantalla final, se copia al portapapeles y llega como notificación („Du wolltest: …“), que caduca a la hora.
- **Regreso al link:** el reto corre en su propia tarea (`taskAffinity` separada, `excludeFromRecents`). Al terminar se llama `finishAndRemoveTask()` y Android muestra la app bloqueada exactamente como estaba, incluyendo el post o la pantalla que abrió el link; nunca se relanza la app. Como respaldo, si en los 2 s siguientes aparece el launcher en vez de la app, el servicio trae de vuelta la tarea existente de esa app.

## 4. Botón de salto y días de pausa

### Salto (3 al día)
- Aparece **1 segundo después** de mostrarse la primera pregunta.
- Es un texto pequeño en la esquina inferior, opacidad ~35%, sin borde: `Überspringen · 2 übrig`.
- Abre la app directamente y otorga la gracia normal de 20 min. El tiempo de uso sigue contando para los límites.
- Contador que se reinicia a las 00:00. Con 0 restantes el botón no aparece.

### Día de pausa (3 al mes)
- Desactiva todas las intercepciones hasta las 00:00.
- Solo desde Einstellungen, con fricción: escribir la frase *„Ich brauche heute wirklich eine Pause.“* y esperar una cuenta atrás de 30 s.
- Contador que se reinicia el día 1 de cada mes. Queda registrado en las estadísticas.

---

## 5. Límites de tiempo

- Límite **por app** y **total** (todas las bloqueadas, navegador incluido), configurables. Se reinician a las **00:00**.
- Número de preguntas al entrar (fuera de la gracia):

```
dentro del límite:        10
límite superado:          20 + 10 × floor(tiempo_excedido / 2 h)
```

  Ejemplo: con 4h 30min por encima del límite → 20 + 10×2 = **40 preguntas**.
- Se usa el mayor exceso entre el límite de esa app y el total.
- **Anti-trampa en límites:** subir un límite o quitar una app de la lista entra en vigor **al día siguiente** y además exige 30 preguntas. Bajar límites o añadir apps se aplica al instante.

---

### Implementación (fase 3)
- **Medición propia** desde el servicio de accesibilidad (cambio de app/sitio en primer plano, pantalla apagada/desbloqueo), guardada por día en `app_usage_day`. Sin permiso extra de *Nutzungsdaten*. Solo se mide el tiempo en apps/sitios bloqueados.
- **Interrupción en medio de la app:** cada minuto se revisa; si estás por encima del límite y ya pasaron tus 20 min de gracia, el reto aparece aunque no hayas salido de la app. Dentro del límite, la gracia solo cuenta al volver a entrar.
- **Navegadores:** se lee solo la barra de direcciones (Chrome, Samsung Internet, Firefox, Brave, Edge). Un sitio bloqueado cuenta como “app” propia (`web:instagram.com`) con su propia gracia y tiempo; los subdominios (`m.`, `vm.`) cuentan. Si estás escribiendo en la barra, no se evalúa.
- **Al desbloquear el teléfono** directamente dentro de una app bloqueada, cuenta como entrar de nuevo.
- Por ahora cambiar límites/apps aplica de inmediato; la regla anti-trampa (subir límites = mañana + 30 preguntas) llega en la fase 5.

## 6. Motor de sesiones

### Selección de tema (sesión interceptada)
Cada ítem se sortea por separado: **Deutsch 70%**, Systemdesign 15%, Interviewfragen 15% (configurable).

### Modo directo
Si abres la app desde el launcher, eliges tema (o „Gemischt“) y estudias sin límite de preguntas. Nunca te redirige a otra app.

### Composición de la sesión
Dentro del tema sorteado, por orden de prioridad:
1. **Repasos vencidos** (`due <= ahora`), el más atrasado primero → flashcard con semáforo.
2. **Tarjetas nuevas** (máx. 20 nuevas al día en total, configurable) → flashcard.
3. **Opción múltiple** sobre tarjetas *gefestigt* o superior: ~30% de la sesión cuando existen.
4. **Fallback:** si no hay nada de lo anterior, tarjetas aleatorias del tema, con preferencia por las de menor estabilidad.

- Un fallo cuenta igual como una de las N preguntas.
- Una tarjeta marcada 🔴 vuelve a aparecer al final de la misma sesión, como en Anki. Esta reaparición **no** cuenta para N.
- No se repite la misma tarjeta (ni su dirección inversa) dentro de una sesión, salvo por 🔴.

---

## 7. Repetición espaciada (FSRS) y niveles de dominio

### Semáforo
| Botón | UI | FSRS |
|---|---|---|
| 🔴 | `Nochmal` | Again |
| 🟡 | `Schwer` | Hard |
| 🟢 | `Gut` | Good |
| 🔵 | `Leicht` | Easy |

Cada botón muestra encima el próximo intervalo (`10 min`, `3 T`, `2 W`…), como Anki.

### Estado por tarjeta (Room)
`stability`, `difficulty`, `due`, `reps`, `lapses`, `consecutiveGreens`, `lastReview`, `level`.

### Niveles (indican qué tan aprendida está cada tarjeta)
| Nivel | Condición |
|---|---|
| `Neu` | nunca vista |
| `Lernen` | vista, < 2 verdes seguidos |
| `Jung` | ≥ 2 verdes seguidos, estabilidad < 21 días |
| `Gefestigt` | ≥ 3 verdes seguidos **y** estabilidad ≥ 21 días → **entra al pool de opción múltiple** |
| `Gemeistert` | estabilidad ≥ 90 días |

Un 🔴 reinicia `consecutiveGreens` y puede bajar de nivel. Las tarjetas mejor aprendidas salen con menos frecuencia porque FSRS amplía su intervalo de forma exponencial.

### Opción múltiple (MCQ)
- Acierto → `Good`. Fallo → `Again` (cuenta como *lapse*).
- Los distractores se generan solos, con 3 por pregunta:
  - Vocabulario: traducciones de tarjetas del mismo tipo y categoría gramatical.
  - Artículo: siempre `der / die / das` (+ plural `die` si aplica).
  - Declinación: las otras formas de la tabla (p. ej. `dem / den / des / der`).
  - Preposición fija: otras preposiciones frecuentes (`auf / an / für / über / mit`).
  - Systemdesign / Interview: respuestas de otras tarjetas del mismo `tag`.
- Una tarjeta puede traer `distractors` manuales que reemplazan a los automáticos.

---

## 8. Contenido (JSON en GitHub)

### Repo
```
moment-mal-content/        (repo privado)
 ├─ manifest.json
 └─ decks/
     ├─ deutsch.json
     ├─ systemdesign.json
     └─ interview.json
```

`manifest.json`
```json
{ "version": 3, "decks": [
  { "id": "deutsch", "name": "Deutsch", "file": "decks/deutsch.json", "weight": 0.70 },
  { "id": "systemdesign", "name": "Systemdesign", "file": "decks/systemdesign.json", "weight": 0.15 },
  { "id": "interview", "name": "Interviewfragen", "file": "decks/interview.json", "weight": 0.15 }
]}
```

### Sync
- Usa un *fine-grained token* de solo lectura de ese repo, guardado en `EncryptedSharedPreferences`.
- Sincroniza al abrir la app, una vez al día y con el botón „Jetzt synchronisieren“. Solo descarga si cambió `manifest.version` (o el SHA del archivo).
- Valida el JSON antes de aplicarlo. Si hay errores, conserva la versión anterior y muestra cuáles fallaron.
- El progreso va ligado al **`id`** de la tarjeta: puedes editar textos sin perder el historial. Las tarjetas eliminadas se archivan pero no se borran.

### Notas vs. tarjetas
Igual que en Anki, cada entrada del JSON es una **nota**. Con `"directions": ["de-es","es-de"]` se generan dos tarjetas: `<id>#de-es` y `<id>#es-de`, cada una con su propio progreso.

### Esquema — Deutsch
Campos comunes: `id`, `type`, `level` (`B1`), `tags`, `directions`, `notes` (opcional), `distractors` (opcional).

| `type` | Contenido | Énfasis |
|---|---|---|
| `nomen` | palabra, `artikel`, `plural`, `genitiv`, traducción, ejemplo | **artículo** (tarjeta extra `artikel` automática) |
| `verb` | infinitivo, `praeteritum`, `perfekt` (con haben/sein), `rektion` (Akk/Dat), ejemplo | |
| `verb_praep` | verbo + preposición fija + caso, ejemplo | preposición y caso |
| `adjektiv` | palabra, comparativo, ejemplo | |
| `ausdruck` | expresión cotidiana, significado, ejemplo | |
| `redewendung` | modismo, significado literal y real, ejemplo | |
| `satz` | oración DE ↔ ES | |
| `deklination` | frase con hueco (cloze), forma correcta, caso, explicación | **artículo + terminación de adjetivo** |
| `grammatik` | cloze/regla (Konjunktiv II, Passiv, Relativsätze, Nebensätze, Wechselpräpositionen…) | |
| `lesetext` | párrafo B1 + 2–4 preguntas de comprensión | sale como MCQ/flashcard por pregunta |

Ejemplos:
```json
{
  "id": "de-n-0001", "type": "nomen", "level": "B1", "tags": ["arbeit"],
  "directions": ["de-es", "es-de"],
  "wort": "Bewerbung", "artikel": "die", "plural": "die Bewerbungen", "genitiv": "der Bewerbung",
  "es": "la solicitud / postulación (de empleo)",
  "beispiel": { "de": "Ich habe meine Bewerbung gestern abgeschickt.", "es": "Envié mi postulación ayer." }
}
```
```json
{
  "id": "de-d-0042", "type": "deklination", "level": "B1", "tags": ["dativ", "adjektiv"],
  "directions": ["cloze"],
  "satz": "Ich helfe ___ alt___ Mann.",
  "loesung": ["dem", "en"], "kasus": "Dativ",
  "erklaerung": "helfen + Dativ; maskulin, bestimmter Artikel → dem …en",
  "es": "Ayudo al hombre mayor."
}
```
```json
{
  "id": "de-vp-0010", "type": "verb_praep", "level": "B1", "tags": ["verben"],
  "directions": ["de-es", "es-de"],
  "verb": "sich freuen", "praep": "auf", "kasus": "Akkusativ",
  "es": "alegrarse por (algo futuro)",
  "beispiel": { "de": "Ich freue mich auf den Urlaub.", "es": "Me hace ilusión las vacaciones." }
}
```

### Esquema — Systemdesign / Interview
```json
{
  "id": "sd-0007", "type": "konzept", "tags": ["caching"],
  "frage": "What is the difference between write-through and write-back caching?",
  "antwort": "Write-through writes to cache and DB synchronously…; write-back writes to cache and flushes later…",
  "details": "Trade-offs: consistency vs. latency…"
}
```
`type`: `konzept`, `tradeoff`, `szenario` (diseña X: puntos clave), `behavioral`, `coding_konzept`.

**Bilingüe:** cada nota trae `en` y `es`. La app **alterna** el idioma en cada aparición de la tarjeta (la última dirección usada se guarda en `card_state`). Ver el esquema exacto en `content/SCHEMA.md`.

### Contenido inicial
| Deck | Notas | Reparto |
|---|---|---|
| Deutsch | 200 | ~45 nomen · 25 verb · 25 verb_praep · 10 adjektiv · 20 ausdruck · 15 redewendung · 15 satz · 25 deklination · 15 grammatik · 5 lesetext |
| Systemdesign | 50 | caching, DBs, sharding, colas, consistencia, CAP, rate limiting, load balancing, diseños clásicos |
| Interview | 50 | ~30 técnicas (estructuras, complejidad, concurrencia, redes, OOP) + ~20 behavioral (STAR) |

---

## 9. Datos (Room)

| Tabla | Contenido |
|---|---|
| `card_state` | estado FSRS + nivel por `cardId` |
| `review_log` | cada respuesta: cardId, rating, modo (flash/mcq), sessionId, timestamp, duración |
| `session` | origen (app/dominio/directo), N pedidas, completada/saltada, inicio/fin |
| `app_usage_day` | fecha, paquete/dominio, minutos |
| `skip_log`, `pause_log` | uso de saltos y pausas |
| `content_meta` | versión/SHA de cada deck, fecha de sync |

---

## 10. Estadísticas (`Statistik`)

- **Racha diaria:** días seguidos con ≥ 10 respuestas. Un día de pausa no rompe la racha, pero tampoco la suma.
- **Tiempo ahorrado:** la línea base es el promedio diario de las apps bloqueadas en los 14 días **antes de instalar** (lo da `UsageStatsManager`). El ahorro de cada día es la línea base menos el uso real. Se muestra por día, por semana y en total.
- **Intercepciones:** cuántas veces abriste una app, cuántas desististe (reto abandonado) y cuántos saltos usaste.
- **Dominio por tema:** barras apiladas Neu / Lernen / Jung / Gefestigt / Gemeistert, más la precisión de los últimos 30 días.
- Previsión de repasos de los próximos 7 días.

---

## 11. Backup

- „Fortschritt exportieren“: un `.json` con todo (card_state, logs, ajustes, contadores). Se guarda con el selector de archivos del sistema o se comparte (Drive, etc.).
- „Importieren“: restaura todo, con confirmación previa.
- Backup automático semanal en `Download/MomentMal/`. Se conservan los últimos 4.

---

## 11b. Implementación (fase 4)
- **Sync:** GitHub Contents API (`application/vnd.github.raw+json`) con un *fine-grained token* de solo lectura, cifrado con una clave del Android Keystore. Se sincroniza al abrir la app (máx. cada 6 h), a diario con WorkManager y con „Jetzt synchronisieren“. Solo se instala si `manifest.version` es mayor y **todo** el contenido parsea; si no, se conserva la versión anterior. Se usa la copia sincronizada salvo que la empaquetada en el APK sea más nueva.
- **Statistik:** Serie (≥10 respuestas/día; los días de pausa ni rompen ni suman), tiempo ahorrado (Σ max(0, Vergleichswert − uso real) por día completo desde la instalación), uso de los últimos 7 días con la línea de referencia, retos de 7 días (gemacht / verzichtet / übersprungen), dominio por tema (escala de un solo tono) + % de aciertos en 30 días, previsión de repasos de 7 días.
- **Vergleichswert:** se calcula de *Nutzungsdaten* de Android (14 días antes de instalar; permiso opcional) o se ajusta a mano.
- **Backup:** exportar/importar con el selector de archivos del sistema; automático semanal en `Download/MomentMal/` (se conservan 4).

## 12. Modo estricto (activado por defecto)

- Device Admin activo: para desinstalar primero hay que desactivarlo.
- Si abres la página de Accesibilidad o de Info de la app de *Moment mal* en Ajustes, sale un reto de **30 preguntas** antes de dejarte entrar.
- Desactivar el modo estricto cuesta 30 preguntas y entra en vigor en 24 h.
- Si el servicio de accesibilidad se desactiva: notificación persistente y registro en las estadísticas.

### Implementación (fase 5)
- **Protección de ajustes:** en Ajustes / Samsung Accessibility / instalador de paquetes, el servicio lee el texto visible. Si la pantalla trata de Moment mal **y** ofrece apagarlo (desinstalar, forzar detención, desactivar; ES/EN/DE) o es nuestra página de accesibilidad (se reconoce por su descripción), aparece un reto de **30 preguntas** sin botón de salto; después hay 5 min libres. Funciona también en días de pausa. Las pantallas de otras apps no se tocan.
- **Lockerungen** (subir límites, desbloquear app/sitio, apagar el modo estricto): 30 preguntas → quedan *pendientes* y se aplican desde el día siguiente (se ven en amarillo: „ab morgen frei“). Endurecer (bloquear, bajar límites, encender el modo) es inmediato y cancela lo pendiente que choque.
- **Deinstallationsschutz:** Geräteadministrator sin políticas; `adb uninstall` / desinstalar falla hasta desactivarlo, y esa pantalla está protegida. Al apagarse el modo estricto (al día siguiente) el administrador se quita solo.
- **Watchdog:** WorkManager cada 15 min: si la Bedienungshilfe está apagada → notificación fija „Moment mal ist aus“ y el día queda registrado (Statistik). También aplica los cambios pendientes que ya vencieron.
- Abrir Ajustes desde la propia configuración de Moment mal da 3 min sin reto (para la puesta en marcha).

---

## 12b. Contenido v2

- 800 notas: Deutsch 400 (A2→B2, incl. `grammatik_frage` para conceptos como Dativ/Akkusativ y 55 oraciones completas), Systemdesign 100, Interviewfragen 100, **Dev** 100 (frontend, backend, UI) y **Produktmanagement** 100 (discovery, estrategia, métricas, UX, delivery, comunicación).
- Cada nota tiene `stufe` (1 leicht · 2 mittel · 3 schwer). Las tarjetas nuevas se introducen de fácil a difícil (75 % del nivel más fácil que quede, 25 % al azar).
- Pesos del reto: Deutsch 70 %, los otros cuatro 7,5 % cada uno.
- La nota del reto ya no se copia sola: icono „Kopieren“ en la pantalla final y acción „Kopieren“ en la notificación.

## 13. UI / diseño

- **Tono:** calma, poco contraste agresivo, mucho espacio, sin animaciones bruscas (fades de 200 ms) y háptica suave al responder.
- **Paleta (oscura):**
  - Fondo `#111418` · superficie `#1A1F25` · borde `#262C33`
  - Texto `#E7E3DA` (blanco cálido) · texto secundario `#8C939C`
  - Acento salvia `#8FB3A3`
  - Semáforo desaturado: rojo `#C88080` · amarillo `#D4B26E` · verde `#8DB895` · azul `#8BA6C6`
- **Tipografía:** *Atkinson Hyperlegible* para la UI y *Literata* para el texto de las tarjetas (lectura larga cómoda). Cuerpo de tarjeta 20–22 sp.
- **Artículos con color sutil** (opcional, ayuda a memorizar): der = azul, die = rojo, das = verde, en el texto de las tarjetas `nomen`.
- **Audio:** botón 🔊 con TTS alemán del sistema en todas las tarjetas DE.

### Pantallas
1. **Challenge (overlay):** barra de progreso `3 / 10`, tarjeta, „Antwort zeigen“, semáforo o 4 opciones (MCQ), botón de salto discreto. Si el reto viene por exceso de límite, una línea arriba en texto suave: *„Du bist heute 2 h 15 min über deinem Limit.“*
2. **Start (modo directo):** saludo, racha, repasos pendientes por tema, botones de tema y „Gemischt“.
3. **Lernen:** igual que Challenge, pero sin límite de preguntas y con „Beenden“.
4. **Statistik**.
5. **Einstellungen:** Apps & Websites, Limits, Themengewichtung, Neue Karten pro Tag, Inhalte (sync/token), Backup, Pausetage, Strenger Modus.
6. **Onboarding:** permisos paso a paso con capturas de Samsung.

### Textos clave
| Contexto | Texto |
|---|---|
| Inicio del reto | „Moment mal. Erst 10 Fragen, dann Instagram.“ |
| Fin del reto | „Geschafft. 20 Minuten gehören dir.“ |
| Salto | „Überspringen · 2 übrig“ |
| Repaso | „Antwort zeigen“ · „Nochmal / Schwer / Gut / Leicht“ |
| Sin pendientes | „Alles wiederholt. Neue Karten?“ |

---

## 14. Plan de implementación

| Fase | Entrega |
|---|---|
| ✅ **0 · Contenido** | Esquema JSON definitivo + validador + 300 notas iniciales · repo privado `monicasofiarestrepo/moment-mal-content` |
| ✅ **1 · Núcleo de estudio** | Proyecto Android, tema, Room, FSRS, carga de JSON desde assets, modo directo (Start + Lernen), MCQ |
| ✅ **2 · Intercepción** | AccessibilityService, ChallengeActivity, gracia de 20 min, salto 3/día, onboarding de permisos Samsung |
| ✅ **3 · Límites y web** | UsageStats, límites por app y total, fórmula de preguntas extra, bloqueo de dominios en navegadores, días de pausa |
| ✅ **4 · Sync, stats, backup** | Sync con GitHub, Statistik, export/import, backup automático |
| ✅ **5 · Modo estricto** | Device Admin, protección de Ajustes, watchdog, reglas anti-trampa de límites |

---

## 15. Decisiones confirmadas

- Nombre: **Moment mal**.
- Apps y dominios editables desde la app; límites por defecto 30 min/app y 90 min en total; gracia por app.
- Navegador: Chrome.
- Entorno: Android Studio en macOS.
- Systemdesign e Interview: bilingües EN/ES, alternando.
