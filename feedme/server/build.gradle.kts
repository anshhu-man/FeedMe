plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }
application { mainClass.set("com.feedme.server.MainKt") }

dependencies {
    implementation(project(":shared:contracts"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.postgresql.jdbc)
    implementation(libs.json.schema.validator)
    implementation(libs.joni)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(project(":shared:contracts"))
    testImplementation(project(":shared:transport"))
}

// One canonical source; never hand-maintain another list of product endpoints.
// The checked-in runtime lock rejects changed bytes until consciously reviewed.
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("../outputs/biteclub_blueprint/architecture/04_API_Contract.json")) {
        rename { "feedme-openapi.json" }
    }
}

tasks.test { testLogging { events("failed", "skipped") } }
tasks.processTestResources {
    from(rootProject.layout.projectDirectory.file("docs/verification/schema-validator-spike/cases.json")) {
        rename { "schema-validator-cases.json" }
    }
    from(rootProject.layout.projectDirectory.file("../outputs/biteclub_blueprint/architecture/05_Events.md")) {
        rename { "canonical-events.md" }
    }
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}
configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("integrationTest") {
    description = "Tests durable state against a new isolated local PostgreSQL cluster; fails if binaries are missing."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    testLogging { events("failed", "skipped") }
}
