# Contributing

Thanks for your interest. Bug reports and pull requests are welcome.

## Build and test
You need JDK 21. Gradle is included.

```sh
./gradlew test
./gradlew releaseJar   # build/libs/azur-launcher-all.jar
```

## Pull requests
- Keep changes small and focused; one topic per pull request.
- Run `./gradlew test` before sending; add a test for new behaviour when it is practical.
- Follow the style of the surrounding code (no dead code, no debug output).
- By contributing you agree that your work is licensed under GPL-3.0, like the rest of the project.

## Reporting a bug
Open an issue with your OS, launcher version, what you did, what you expected and what happened.
Attach the launcher log if you can. Do not post tokens or passwords.
