# Moment mal

App personal de Android que intercepta apps y sitios distractores (estilo one sec) y exige
flashcards con repetición espaciada (FSRS) antes de dejarte entrar: alemán B1, diseño de sistemas
y preguntas de entrevista.

- [SPEC.md](SPEC.md): especificación completa y estado de cada fase
- [android/](android/): la app (Kotlin + Jetpack Compose) · cómo compilar e instalar en [android/README.md](android/README.md)
- [content/](content/): submódulo → repo de contenido `moment-mal-content` (JSON por tema)

```bash
git clone --recurse-submodules https://github.com/monicasofiarestrepo/moment-mal-app.git
```
El build copia `content/manifest.json` y `content/decks/` al APK, así que el submódulo es necesario.
