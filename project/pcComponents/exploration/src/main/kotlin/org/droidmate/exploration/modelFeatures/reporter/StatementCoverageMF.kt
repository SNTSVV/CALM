// DroidMate, an automated execution generator for Android apps.
// Copyright (C) 2012-2018. Saarland University
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// Current Maintainers:
// Nataniel Borges Jr. <nataniel dot borges at cispa dot saarland>
// Jenny Hotzkow <jenny dot hotzkow at cispa dot saarland>
//
// Former Maintainers:
// Konrad Jamrozik <jamrozik at st dot cs dot uni-saarland dot de>
//
// web: www.droidmate.org

package org.droidmate.exploration.modelFeatures.reporter

import com.natpryce.konfig.PropertyGroup
import com.natpryce.konfig.booleanType
import com.natpryce.konfig.getValue
import com.natpryce.konfig.stringType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.droidmate.coverage.INSTRUMENTATION_FILE_SUFFIX
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.modelFeatures.ModelFeature
import org.droidmate.exploration.modelFeatures.autaut.RegressionTestingMF
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.misc.deleteDir
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.coroutines.CoroutineContext

/**
 * Model feature to monitor the statement coverage by processing an optional instrumentation file and fetching
 * the statement data from the device.
 */
class StatementCoverageMF(private val statementsLogOutputDir: Path,
                          private val readStatements: suspend ()-> List<List<String>>,
                          private val appName: String,
                          private val resourceDir: Path,
                          private val statementFileName: String = "coverage.txt",
                          private val methodFileName: String = "methodCoverage.txt",
                          private val modifiedMethodFileName: String = "modifiedMethodCoverage.txt") : ModelFeature() {
    override val coroutineContext: CoroutineContext = CoroutineName("StatementCoverageMF") + Job()

     val executedMethodsMap: ConcurrentHashMap<String, Date> = ConcurrentHashMap() //methodid -> first executed
     val executedModifiedMethodsMap: ConcurrentHashMap<String, Date> = ConcurrentHashMap() // methodid -> first executed
     val executedModifiedMethodStatementsMap: ConcurrentHashMap<String, Date> = ConcurrentHashMap() // methodid -> first executed
     val statementInstrumentationMap= HashMap<String, String>() //statementid -> statement
     val methodStatementInstrumentationMap = HashMap<String, String>() //statementid -> methodid
     val methodInstrumentationMap= HashMap<String, String>() //method id -> method
     val modMethodInstrumentationMap= HashMap<String, String>() //method id -> method
     val modMethodStatementInstrumentationMap= HashMap<String, String>() //method id -> method
     val executedStatementsMap: ConcurrentHashMap<String, Date> = ConcurrentHashMap()

    val recentExecutedStatements: ArrayList<String> = ArrayList()
    val recentExecutedMethods: ArrayList<String> = ArrayList()

    var statementRead:Boolean = false
    //private val instrumentationMap = getInstrumentation(appName)
    val mutex = Mutex()

    private var trace: ExplorationTrace<*,*>? = null

    init {
        assert(statementsLogOutputDir.deleteDir()) { "Could not delete the directory $statementsLogOutputDir" }
        Files.createDirectories(statementsLogOutputDir)
        getInstrumentation(appName)
    }

    override fun onAppExplorationStarted(context: ExplorationContext<*, *, *>) {
        this.trace = context.explorationTrace

    }

    override suspend fun onContextUpdate(context: ExplorationContext<*, *, *>) {
        statementRead = false
        mutex.withLock {
            // Fetch the statement data from the device

            val readStatements = readStatements()

            readStatements
                    .forEach { statement ->
                        val timestamp = statement[0]
                        val tms = dateFormat.parse(timestamp)
                        //NGO
                        val parts = statement[1].toString().split(" uuid=".toRegex(), 2).toTypedArray()
                        val statementId = parts.last()
                        recentExecutedStatements.add(statementId)
                        var found = executedStatementsMap.containsKey(statementId)
                        if (!found /*&& instrumentationMap.containsKey(id)*/)
                            executedStatementsMap[statementId] = tms
                        val parts2 = parts[0].split(" methodId=".toRegex(),2)
                        if (parts2.size > 1)
                        {
                            // val l = "9946a686-9ef6-494f-b893-ac8b78efb667"
                            val methodId = parts2.last()
                            // Add the statement if it wasn't executed before
                            recentExecutedMethods.add(methodId)
                            found = executedMethodsMap.contains(methodId)
                            if (!found)
                            {
                                executedMethodsMap[methodId] = tms
                            }
                            val modMethod = modMethodInstrumentationMap.contains(methodId)
                            if (modMethod)
                            {
                                found = executedModifiedMethodsMap.contains(methodId)
                                if (!found)
                                {
                                    executedModifiedMethodsMap[methodId] = tms
                                }
                                found = executedModifiedMethodStatementsMap.contains(statementId)
                                if (!found)
                                {
                                    executedModifiedMethodStatementsMap[statementId] = tms
                                }
                            }
                        }
                    }

            log.info("Current statement coverage: ${"%.2f".format(getCurrentCoverage())}. Encountered statements: ${executedStatementsMap.size}")
            log.info("Current method coverage: ${"%.2f".format(getCurrentMethodCoverage())}. Encountered methods: ${executedMethodsMap.size}")
            log.info("Current modified method coverage: ${"%.2f".format(getCurrentModifiedMethodCoverage())}. Encountered modified methods: ${executedModifiedMethodsMap.size}")
            log.info("Current modified method's statement coverage: ${"%.2f".format(getCurrentModifiedMethodStatementCoverage())}. Encountered modified methods: ${executedModifiedMethodStatementsMap.size}")

            // Write the received content into a file
            if (readStatements.isNotEmpty()) {
                val lastId = trace?.last()?.actionId ?: 0
                val file = getLogFilename(lastId)
                withContext(Dispatchers.IO){ Files.write(file, readStatements.map { "${it[1]};${it[0]}" }) }
            }

        }
        statementRead = true

    }
    /**
     * Fetch the statement data form the device. Afterwards, it parses the data and updates [executedStatementsMap].
     */
    override suspend fun onNewAction(traceId: UUID, interactions: List<Interaction<*>>, prevState: State<*>, newState: State<*>) {
        // This code must be synchronized, otherwise it may read the
        // same statements multiple times

    }

    /**
     * Returns a map which is used for the coverage calculation.
     */
    private fun getInstrumentation(apkName: String){
       if (!Files.exists(resourceDir)) {
            log.warn("Provided Dir does not exist: $resourceDir." +
                "DroidMate will monitor coverage, but won't be able to calculate the coverage.")
        } else {
            val instrumentationFile = getInstrumentationFile(apkName, resourceDir)
            if (instrumentationFile != null)
                readInstrumentationFile(instrumentationFile)
            else {
                log.warn("Provided directory ($resourceDir) does not contain " +
                    "the corresponding instrumentation file. DroidMate will monitor coverage, but won't be able" +
                    "to calculate the coverage.")
            }

        }
    }

    /**
     * Returns the the given instrumentation file corresponding to the passed [apkName].
     * Returns null, if the instrumentation file is not present.
     *
     * Example:
     * apkName = a2dp.Vol_137.apk
     * return: instrumentation-a2dp.Vol.json
     */
    private fun getInstrumentationFile(apkName: String, targetDir: Path): Path? {
        return Files.list(targetDir)
            .filter {
                it.fileName.toString().contains(apkName)
                    && it.fileName.toString().endsWith(".apk$INSTRUMENTATION_FILE_SUFFIX")
            }
            .findFirst()
            .orElse(null)
    }

    @Throws(IOException::class)
    private fun readInstrumentationFile(instrumentationFile: Path?){
//        val jsonData = String(Files.readAllBytes(instrumentationFile))
//        val jObj = JSONObject(jsonData)
//
//        val jMap = jObj.getJSONObject(INSTRUMENTATION_FILE_METHODS_PROP)
//        val statements = mutableMapOf<Long, String>()
//
//        jMap.keys()
//            .asSequence()
//            .forEach { key ->
//                val keyId = key.toLong()
//                val value = jMap[key]
//                statements[keyId] = value.toString()
//            }
//
//        return statements
        val jsonData = String(Files.readAllBytes(instrumentationFile))
        val jObj = JSONObject(jsonData)

        val l = "9946a686-9ef6-494f-b893-ac8b78efb667".length
        var jMap = jObj.getJSONObject("allMethods")
        jMap.keys()
                .asSequence()
                .forEach { key ->
                    val keyId = key.toLong()
                    val method = jMap[key]
                    //check for modified method
                    //example format: "modified=true <com.teleca.jamendo.window.SearchActivity$SearchingDialog: void playlistSearch()> uuid=3b389bcf-70e4-400b-afce-c0e67d682333"
                    val modified = method.toString().contains("modified=true")

                    if (modified)
                    {
                        //get uuid
                        val index = method.toString().indexOf("modified=true")
                        val methodInfo = method.toString().substring(index+"modified=true ".length)
                        val parts = methodInfo.split(" uuid=")
                        val uuid = parts[1]
                        assert(uuid.length == l, { "Invalid UUID $uuid $method" })
                        modMethodInstrumentationMap[uuid] = parts[0]
                        methodInstrumentationMap[uuid] = parts[0]
                    }
                    else
                    {

                        val parts = method.toString().split(" uuid=")
                        val uuid = parts[1]
                        assert(uuid.length == l, { "Invalid UUID $uuid $method" })
                        methodInstrumentationMap[uuid] = parts[0]
                    }

                }
        log.info("methods : ${methodInstrumentationMap.size}")
        log.info("modified methods : ${modMethodInstrumentationMap.size} - ${modMethodInstrumentationMap.size}*100/${methodInstrumentationMap.size}%")

        //NGO change
        //val jMap = jObj.getJSONObject(INSTRUMENTATION_FILE_METHODS_PROP)
        jMap = jObj.getJSONObject("allStatements")

        jMap.keys()
                .asSequence()
                .forEach { key ->
                    val keyId = key.toLong()
                    val statement = jMap[key]
                    val parts = statement.toString().split(" uuid=".toRegex(), 2).toTypedArray()
                    val uuid = parts.last()
                    assert(uuid.length == l, { "Invalid UUID $uuid $statement" })
                    statementInstrumentationMap[uuid] = statement.toString()
                    val parts2 = parts[0].split(" methodId=".toRegex(),2)
                    if (parts2.size > 1)
                    {
                        // val l = "9946a686-9ef6-494f-b893-ac8b78efb667"
                        val methodId = parts2.last()
                        // Add the statement if it wasn't executed before
                        methodStatementInstrumentationMap[uuid] = methodId
                        if (isModifiedMethod(methodId))
                        {
                            modMethodStatementInstrumentationMap[uuid] = methodId
                        }
                    }
                }
        log.info("statement : ${statementInstrumentationMap.size}")
    }

    /**
     * Returns the current measured coverage.
     * Note: Returns 0 if [instrumentationMap] is empty.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getCurrentCoverage(): Double {
        return if (statementInstrumentationMap.isEmpty()) {
            0.0
        } else {
            assert(executedStatementsMap.size <= statementInstrumentationMap.size) {
                "Reached statements exceed total numbers of statements in the app"
            }
            executedStatementsMap.size / statementInstrumentationMap.size.toDouble()
        }
    }

    fun getCurrentMethodCoverage(): Double {
        return if (methodInstrumentationMap.isEmpty()) {
            0.0
        } else {
            assert(executedMethodsMap.size <= methodInstrumentationMap.size) {
                "Reached statements exceed total numbers of statements in the app"
            }
            executedMethodsMap.size / methodInstrumentationMap.size.toDouble()
        }
    }

    fun getCurrentModifiedMethodCoverage(): Double {
        return if (modMethodInstrumentationMap.isEmpty()) {
            0.0
        } else {
            assert(executedModifiedMethodsMap.size <= modMethodInstrumentationMap.size) {
                "Reached statements exceed total numbers of statements in the app"
            }

            executedModifiedMethodsMap.size / modMethodInstrumentationMap.size.toDouble()
        }
    }

    fun getCurrentModifiedMethodStatementCoverage(): Double{
        return if (modMethodInstrumentationMap.isEmpty()) {
            0.0
        } else {
            executedModifiedMethodStatementsMap.size / modMethodStatementInstrumentationMap.size.toDouble()
        }
    }
    fun isModifiedMethod(modifiedMethodId: String): Boolean{
        if (modMethodInstrumentationMap.containsKey(modifiedMethodId))
            return true
        return false
    }

    fun isModifiedMethodStatement(statementId: String): Boolean {
        val methodId = methodStatementInstrumentationMap[statementId]!!
        if (isModifiedMethod(methodId))
            return true
        return false
    }

    fun getMethodName(methodId: String): String{
        return methodInstrumentationMap[methodId]?:""
    }

    fun getMethodId(methodName: String): String{
        return methodInstrumentationMap.filterValues { it.equals(methodName) }.entries.firstOrNull()?.key?:""
    }
    //Return List of statment id
    fun getMethodStatements(methodId: String): List<String>{
        return methodStatementInstrumentationMap.filter { it.value.equals(methodId) }.map { it.key }
    }

    /**
     * Return list of modified methods'id
     */
    fun getAllModifiedMethodsId(): List<String>{
        return modMethodInstrumentationMap.map { it.key }
    }

    /**
     * Return list of executed statements
     */

    fun getAllExecutedStatements(): List<String>{
        return executedStatementsMap.map { it.key }
    }
    /**
     * Returns the logfile name depending on the [counter] in which the content is written into.
     */
    private fun getLogFilename(counter: Int): Path {
        return statementsLogOutputDir.resolve("$appName-statements-%04d".format(counter))
    }

    override suspend fun onAppExplorationFinished(context: ExplorationContext<*, *, *>) {
        this.join()
        regressionTestingMF = context.findWatcher { it is RegressionTestingMF } as RegressionTestingMF?
        produceStatementCoverageOutput(context)
        produceMethodCoverageOutput(context)
        produceModMethodCoverageOutput(context)
    }
    fun produceStatementCoverageOutput(context: ExplorationContext<*,*,*>){
        val sb = StringBuilder()
        sb.appendln("Total statements: ${statementInstrumentationMap.size}")
        sb.appendln("Total covered statements: ${executedStatementsMap.size}")
        sb.appendln(statement_header)
        if (executedStatementsMap.isNotEmpty()) {
            val sortedStatements = executedStatementsMap.entries
                    .sortedBy { it.value }
            val initialDate = sortedStatements.first().value

            sortedStatements
                    .forEach {
                        val method = methodStatementInstrumentationMap[it.key]
                        sb.appendln("${it.key};$method;${Duration.between(initialDate.toInstant(), it.value.toInstant()).toMillis() / 1000}")
                    }
        }
        statementInstrumentationMap.filterNot { executedStatementsMap.containsKey(it.key) }.forEach {
            val method = methodStatementInstrumentationMap[it.key]
            sb.appendln("${it.key};$method")
        }
        val outputFile = context.model.config.baseDir.resolve(statementFileName)
        log.info("Prepare writing coverage file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}")

        Files.write(outputFile, sb.lines())
        log.info("Finished writing coverage in ${outputFile.fileName}")
    }

    fun produceMethodCoverageOutput(context: ExplorationContext<*,*,*>){
        val sb = StringBuilder()
        sb.appendln("Total methods: ${methodInstrumentationMap.size}")
        sb.appendln("Total covered methods: ${executedMethodsMap.size}")
        sb.appendln(method_header)
        if (executedMethodsMap.isNotEmpty()) {
            val sortedMethods = executedMethodsMap.entries
                    .sortedBy { it.value }
            val initialDate = sortedMethods.first().value

            sortedMethods
                    .forEach {
                        val methodName = methodInstrumentationMap[it.key]
                        sb.appendln("${it.key};$methodName;${Duration.between(initialDate.toInstant(), it.value.toInstant()).toMillis() / 1000}")
                    }
        }

        val outputFile = context.model.config.baseDir.resolve(methodFileName)
        log.info("Prepare writing coverage file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}")

        Files.write(outputFile, sb.lines())
        log.info("Finished writing coverage in ${outputFile.fileName}")
    }
    private var regressionTestingMF: RegressionTestingMF?=null
    fun produceModMethodCoverageOutput(context: ExplorationContext<*,*,*>){
        val sb = StringBuilder()
        sb.appendln(statement_header)
        sb.appendln("Statements;${statementInstrumentationMap.size}")
        sb.appendln("Methods;${methodInstrumentationMap.size}")
        sb.appendln("ModifiedMethods;${modMethodInstrumentationMap.size}")
        sb.appendln("ModifiedMethodsStatements;${
                methodStatementInstrumentationMap.filter { modMethodInstrumentationMap.contains(it.value) }.size
        } ")
        sb.appendln("CoveredStatements;${executedStatementsMap.size}")
        sb.appendln("CoveredMethods;${executedMethodsMap.size}")
        sb.appendln("CoveredModifiedMethods;${executedModifiedMethodsMap.size}")
        val executedModifiedMethodStatement = executedStatementsMap.filter { modMethodInstrumentationMap.contains(methodStatementInstrumentationMap[it.key]) }
        sb.appendln("CoveredModifiedMethodsStatements;${executedModifiedMethodStatement.size}")
        sb.appendln("ListCoveredModifiedMethods")
        if (executedModifiedMethodsMap.isNotEmpty()) {
            val sortedMethods = executedModifiedMethodsMap.entries
                    .sortedBy { it.value }
            val initialDate = sortedMethods.first().value

            sortedMethods
                    .forEach {
                        sb.appendln("${it.key};${modMethodInstrumentationMap[it.key]};${Duration.between(initialDate.toInstant(), it.value.toInstant()).toMillis() / 1000}")
                    }

        }
        sb.appendln("ListUnCoveredModifiedMethods")
        modMethodInstrumentationMap.filter {!executedModifiedMethodsMap.containsKey(it.key) }.forEach{
            sb.appendln("${it.key};${it.value}")
        }
        val outputFile = context.model.config.baseDir.resolve(modifiedMethodFileName)
        log.info("Prepare writing coverage file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}")

        Files.write(outputFile, sb.lines())
        log.info("Finished writing coverage in ${outputFile.fileName}")
    }
    companion object {
        private const val statement_header = "Statement(id);Method id;Time(Duration in sec till first occurrence)"
        private const val method_header = "Method(id);Method name;Time(Duration in sec till first occurrence)"

        @JvmStatic
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(StatementCoverageMF::class.java) }

        object StatementCoverage : PropertyGroup() {
            val enableCoverage by booleanType
            val onlyCoverAppPackageName by booleanType
            val coverageDir by stringType
        }

    }

}