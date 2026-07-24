package testagent

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class TestFailure(
    val testName: String,
    val message: String,
    val stackTrace: String,
)

data class TestCase(
    val name: String,
    val failure: TestFailure?, // null = passed
)

data class TestRunResult(
    val total: Int,
    val cases: List<TestCase>,
) {
    val failed: List<TestFailure> get() = cases.mapNotNull { it.failure }
    val passed: Boolean get() = total > 0 && failed.isEmpty()
}

object JUnitXmlParser {

    /** Parses Gradle's JUnit XML reports for files matching [classFilter]. */
    fun parse(resultsDir: File, classFilter: String): TestRunResult {
        if (!resultsDir.exists()) return TestRunResult(0, emptyList())

        val cases = mutableListOf<TestCase>()

        resultsDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .filter { it.name.contains(classFilter) }
            .forEach { file ->
                val doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(file)
                val nodes = doc.getElementsByTagName("testcase")
                for (i in 0 until nodes.length) {
                    val case = nodes.item(i) as Element
                    val name = case.getAttribute("name")
                    val fails = case.getElementsByTagName("failure")
                    val failure = if (fails.length > 0) {
                        val f = fails.item(0) as Element
                        TestFailure(
                            testName = name,
                            message = f.getAttribute("message"),
                            stackTrace = f.textContent.take(2000),
                        )
                    } else null
                    cases += TestCase(name, failure)
                }
            }

        return TestRunResult(cases.size, cases)
    }

    /** Deletes stale result XMLs for [classFilter] so old runs don't pollute parsing. */
    fun clearStaleResults(resultsDir: File, classFilter: String) {
        if (!resultsDir.exists()) return
        resultsDir.walkTopDown()
            .filter { it.isFile && it.name.contains(classFilter) && it.extension == "xml" }
            .forEach { it.delete() }
    }
}
