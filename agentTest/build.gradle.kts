plugins {
    `kotlin-dsl` // applies java-gradle-plugin too
    `maven-publish`
}

group = "com.ceylonapz"
version = "1.1.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}

gradlePlugin {
    plugins {
        create("agentTest") {
            id = "com.ceylonapz.agenttest"
            implementationClass = "testagent.AgentTestPlugin"
        }
    }
}
