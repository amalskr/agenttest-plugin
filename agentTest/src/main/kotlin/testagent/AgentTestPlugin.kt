package testagent

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Applying this plugin registers the `agentTest` task on the module.
 */
class AgentTestPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register<AgentTestTask>("agentTest") {
            group = "verification"
            description =
                "Generates JUnit tests for changed classes, runs them, auto-repairs failures"
        }
    }
}
