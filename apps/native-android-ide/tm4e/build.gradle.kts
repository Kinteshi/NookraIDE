// Eclipse tm4e's core (EPL-2.0): TextMate grammars and themes, as the app's editor colours code. See README.md.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("com.google.code.gson:gson:2.13.1")
    implementation("org.jruby.joni:joni:2.2.6")
    implementation("org.jruby.jcodings:jcodings:1.0.63")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    // Null annotations only: not needed at run time.
    compileOnly("org.eclipse.jdt:org.eclipse.jdt.annotation:2.3.100")
}
