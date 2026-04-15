- [x] Verify that the copilot-instructions.md file in the .github directory is created.

- [x] Clarify Project Requirements
      Java Maven project for a low-overhead servlet request capture javaagent jar targeting WebSphere 9.x style `javax.servlet` traffic.

- [x] Scaffold the Project
      Created Maven project structure, source folders, `pom.xml`, `README.md`, and `.gitignore` in the current workspace.

- [x] Customize the Project
      Implemented a Byte Buddy based javaagent that logs request start and end events, selected headers, request timing, and a bounded body preview for matching content types.

- [x] Install Required Extensions
      No extensions needed.

- [x] Compile the Project
      Built the project with Maven and produced the shaded agent jar under `target/`.

- [x] Create and Run Task
      Added and ran a VS Code build task for `mvn -q test package`.

- [x] Launch the Project
      Skipped. This project produces a javaagent jar and is attached to a target JVM rather than launched as a standalone application.

- [x] Ensure Documentation is Complete
      `README.md` documents build output, agent arguments, and WebSphere-focused usage notes.
