/*
 * ATUA is a test automation tool for mobile Apps, which focuses on testing methods updated in each software release.
 * Copyright (C) 2019 - 2021 University of Luxembourg
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.atua.modelFeatures

import com.natpryce.konfig.PropertyGroup
import com.natpryce.konfig.booleanType
import com.natpryce.konfig.doubleType
import com.natpryce.konfig.getValue
import com.natpryce.konfig.intType
import com.natpryce.konfig.stringType
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.calm.AppModelLoader
import org.calm.ModelBackwardAdapter
import org.calm.TargetInputClassification
import org.calm.TargetInputReport
import org.calm.ewtgdiff.AdditionSet
import org.calm.ewtgdiff.EWTGDiff
import org.calm.modelReuse.ModelHistoryInformation
import org.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.atua.modelFeatures.dstg.DSTG
import org.atua.modelFeatures.dstg.PredictedAbstractState
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.EWTG
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.atua.modelFeatures.helper.ProbabilityDistribution
import org.atua.modelFeatures.inputRepo.deviceEnvironment.DeviceEnvironmentConfiguration
import org.atua.modelFeatures.inputRepo.intent.IntentFilter
import org.atua.modelFeatures.inputRepo.textInput.InputConfiguration
import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.GlobalAction
import org.droidmate.deviceInterface.exploration.Rectangle
import org.droidmate.deviceInterface.exploration.Swipe
import org.droidmate.deviceInterface.exploration.isFetch
import org.droidmate.deviceInterface.exploration.isLaunchApp
import org.droidmate.deviceInterface.exploration.isQueueEnd
import org.droidmate.deviceInterface.exploration.isQueueStart
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.availableActions
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.swipeDown
import org.droidmate.exploration.actions.swipeLeft
import org.droidmate.exploration.actions.swipeRight
import org.droidmate.exploration.actions.swipeUp
import org.droidmate.exploration.modelFeatures.ModelFeature
import org.droidmate.exploration.modelFeatures.explorationWatchers.CrashListMF
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.droidmate.exploration.modelFeatures.graph.StateGraphMF
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.atua.strategy.task.FailReachingLog
import org.atua.strategy.task.GoToAnotherWindowTask
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.emptyUUID
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.droidmate.explorationModel.plus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import javax.imageio.ImageIO
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class ATUAMF(
    private val appName: String,
    private val resourceDir: Path,
    private val manualInput: Boolean,
    private val manualIntent: Boolean,
    val reuseBaseModel: Boolean,
    val reuseSameVersionModel: Boolean,
    private val baseModelDir: Path,
    val getCurrentActivity: suspend () -> String,
    val getDeviceRotation: suspend () -> Int
) : ModelFeature() {
    var doNotRefine: Boolean = false
    private var extraWebViewAbstractTransition: AbstractTransition? = null
    val packageName = appName
    var portraitScreenSurface = Rectangle.empty()
    private var portraitVisibleScreenSurface = Rectangle.empty()
    private var landscapeScreenSurface = Rectangle.empty()
    private var landscapeVisibleScreenSurface = Rectangle.empty()
    private val targetWidgetFileName = "atua-report.txt"
    override val coroutineContext: CoroutineContext = CoroutineName("RegressionTestingModelFeature") + Job()
    var statementMF: StatementCoverageMF? = null
    private var crashlist: CrashListMF? = null
    var wtg: EWTG = EWTG()
    lateinit var dstg: DSTG
    private var stateGraph: StateGraphMF? = null

    var actionProcessedByATUAStrategy: Boolean = false
    var isRecentItemAction: Boolean = false
    private var isRecentPressMenu: Boolean = false

    private var currentRotation: org.atua.modelFeatures.Rotation = org.atua.modelFeatures.Rotation.PORTRAIT

    private val widgetProbability = mutableMapOf<UUID, Double>() // probability of each widget invoking modified methods

    val allTargetStaticWidgets = hashSetOf<EWTGWidget>() //widgetId -> idWidget
    val notFullyExercisedTargetInputs = hashSetOf<Input>()
    val modifiedMethodsByWindow = hashMapOf<Window, HashSet<String>>()
    val allTargetHandlers = hashSetOf<String>()
    val allEventHandlers = hashSetOf<String>()
    val allModifiedMethod = hashMapOf<String, Boolean>()

    val allDialogOwners = hashMapOf<String, ArrayList<String>>() // window -> listof (Dialog)

    private val targetInputsByWindowClass = mutableMapOf<String, ArrayList<Input>>()

    val targetItemEvents = HashMap<Input, HashMap<String, Int>>()
    var isAlreadyRegisteringEvent = false
    private val stateActivityMapping = mutableMapOf<State<*>, String>()

    private val dateFormater = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    private var lastExecutedTransition: AbstractTransition? = null
    private var necessaryCheckModel: Boolean = false

    private var isModelUpdated: Boolean = false
        private set


    val openNavigationCheck = ArrayList<AbstractState>()
    var appRotationSupport = true
    var internetStatus = true
    val abstractStateVisitCount = HashMap<AbstractState, Int>()
    val windowVisitCount = HashMap<Window, Int>()
    val stateVisitCount = HashMap<UUID, Int>()
    val stateStructureHashMap = HashMap<UUID,UUID>()
    var appPrevState: State<*>? = null
//    var windowStack: Stack<Window> = Stack()
    private var abstractStateStack: Stack<AbstractState> = Stack()
    var guiStateStack: Stack<State<*>> = Stack()
    val stateList: ArrayList<State<*>> = ArrayList()


    private val actionScore = HashMap<Triple<UUID?, String, String>, MutableMap<Window, Double>>()
    private val actionScore2 = HashMap<Input, Double>()
    val actionCount = org.atua.modelFeatures.ActionCount()
    var traceId = 0
    private var transitionId = 0

    val interactionsTracingMap = HashMap<List<Interaction<*>>, Pair<Int, Int>>()
     val tracingInteractionsMap = HashMap<Pair<Int, Int>, List<Interaction<*>>>()

        val interactionPrevWindowStateMapping = HashMap<Interaction<Widget>, State<*>>()


    private var lastOpeningAnotherAppInteraction: Interaction<Widget>? = null

    var updateMethodCovFromLastChangeCount: Int = 0
    private var updateStmtCovFromLastChangeCount: Int = 0
    private var methodCovFromLastChangeCount: Int = 0
    var stmtCovFromLastChangeCount: Int = 0
    private var lastUpdatedMethodCoverage: Double = 0.0
    private var lastMethodCoverage: Double = 0.0
    var lastUpdatedStatementCoverage: Double = 0.0


    val unreachableModifiedMethods = ArrayList<String>()

    val intentFilters = HashMap<String, ArrayList<IntentFilter>>()
    val targetIntFilters = HashMap<IntentFilter, Int>()
    var inputConfiguration: InputConfiguration? = null
    var deviceEnvironmentConfiguration: DeviceEnvironmentConfiguration? = null

    val inputWindowCorrelation = HashMap<Input, HashMap<Window, Double>>()
    val untriggeredTargetHiddenHandlers = hashSetOf<String>()

    val disablePrevAbstractStates = HashMap<AbstractAction, HashMap<AbstractState, HashSet<AbstractState>>>()

    private var phase1MethodCoverage: Double = 0.0
    private var phase2MethodCoverage: Double = 0.0
    private var phase1ModifiedMethodCoverage: Double = 0.0
    private var phase2ModifiedCoverage: Double = 0.0
    private var phase1StatementCoverage: Double = 0.0
    private var phase2StatementCoverage: Double = 0.0
    private var phase1ModifiedStatementCoverage: Double = 0.0
    private var phase2ModifiedStatementCoverage: Double = 0.0
    private var phase1Actions: Int = 0
    private var phase2Actions: Int = 0
    var phase3Actions: Int = 0
    private var phase2StartTime: String = ""
    private var phase3StartTime: String = ""
    private var extraInfos = HashMap<String,String>() // Key -> Info
    private var firstReachingStateUUID = HashMap<UUID,Int>()

    private fun setPhase2StartTime() {
        phase2StartTime = dateFormater.format(System.currentTimeMillis())
    }

    private fun setPhase3StartTime() {
        phase3StartTime = dateFormater.format(System.currentTimeMillis())
    }


    fun getTargetIntentFilters(): List<IntentFilter> {
        return targetIntFilters.filter { it.value < 1 }.map { it.key }
    }

    private var mainActivity = ""


    /**
     * Mutex for synchronization
     *
     *
     */
    private val mutex = Mutex()
    private var trace: ExplorationTrace<*, *>? = null
    private var eContext: ExplorationContext<*, *, *>? = null
    private var fromLaunch = true
    private var firstRun = true

    //region Model feature override
    override suspend fun onAppExplorationFinished(context: ExplorationContext<*, *, *>) {
        this.join()
        traceId++
        transitionId=0
        produceATUACoverageReport(context)
        ATUAModelOutput.dumpModel(context.model.config, this, context)
    }

    override fun onAppExplorationStarted(context: ExplorationContext<*, *, *>) {
        this.eContext = context
        this.trace = context.explorationTrace
        this.stateGraph = context.getOrCreateWatcher()
        this.statementMF = context.getOrCreateWatcher()
        this.crashlist = context.getOrCreateWatcher()

        org.atua.modelFeatures.StaticAnalysisJSONParser.Companion.readAppModel(
            getAppModelFile()!!,
            this,
            manualIntent,
            manualInput
        )
        removeDuplicatedWidgets()
        processOptionsMenusWindow()
        for (window in WindowManager.instance.updatedModelWindows) {
            window.inputs.forEach {
                if (it.modifiedMethods.isNotEmpty()) {
                    TargetInputReport.INSTANCE.targetIdentifiedByStaticAnalysis.add(it)
                    TargetInputClassification.INSTANCE.identifiedTargetInputsByStaticAnalysis.put(it,HashMap(it.modifiedMethods))
                    if (!notFullyExercisedTargetInputs.contains(it))
                        notFullyExercisedTargetInputs.add(it)
                }
            }
        }
        AbstractStateManager.INSTANCE.init(this, appName)
        AbstractStateManager.INSTANCE.initVirtualAbstractStates()

        val targetHandlers = modifiedMethodWithTopCallers.values.flatten().distinct()
        val topCallerToModifiedMethods = HashMap<String, HashSet<String>>()
        for (entry in modifiedMethodWithTopCallers.entries) {
            entry.value.forEach {
                topCallerToModifiedMethods.putIfAbsent(it, HashSet())
                topCallerToModifiedMethods[it]!!.add(entry.key)
            }
        }
        var nondeterminismCnt = 0
        if (reuseBaseModel) {
            org.atua.modelFeatures.ATUAMF.Companion.log.info("Loading base model...")
            loadBaseModel()
            AbstractStateManager.INSTANCE.initVirtualAbstractStates()
            dstg.edges().forEach {
                if (it.label.nondeterministic) {
                    it.label.activated = false
                    nondeterminismCnt++
                }
            }
        }

        postProcessingTargets()

        AbstractStateManager.INSTANCE.initAbstractInteractionsForVirtualAbstractStates()
        /* dstg.edges().forEach {
             if (it.label.source !is VirtualAbstractState && it.label.dest !is VirtualAbstractState) {
                 AbstractStateManager.INSTANCE.addImplicitAbstractInteraction(
                         currentState = null,
                         abstractTransition = it.label,
                         transitionId = null)
             }
         }*/
        appPrevState = null

    }

    private fun postProcessingTargets() {

        val beforeProcessedTargetCount = notFullyExercisedTargetInputs.size
        untriggeredTargetHiddenHandlers.clear()

        allModifiedMethod.entries.removeIf { !statementMF!!.modMethodInstrumentationMap.containsKey(it.key) }
        allEventHandlers.addAll(windowHandlersHashMap.map { it.value }.flatten())
        WindowManager.instance.updatedModelWindows.forEach { window ->
            window.inputs.forEach { input ->
                val coveredMethods = input.coveredMethods.map { statementMF!!.methodInstrumentationMap[it.key] }
                val toremove =
                    input.modifiedMethods.filter { !statementMF!!.modMethodInstrumentationMap.containsKey(it.key) }.keys
                toremove.forEach { method ->
                    input.modifiedMethods.remove(method)
                }
            }
            if (windowHandlersHashMap.containsKey(window)) {
                val hiddenHandlers =
                    windowHandlersHashMap[window]!!.subtract(window.inputs.map { it.coveredMethods.keys }.flatten())
                val targetHiddenHandlers =
                    modifiedMethodWithTopCallers.values.flatten().distinct().intersect(hiddenHandlers)
                untriggeredTargetHiddenHandlers.addAll(targetHiddenHandlers)
            }
        }
        modifiedMethodWithTopCallers.entries.removeIf { !statementMF!!.modMethodInstrumentationMap.containsKey(it.key) }
        val targetHandlers = modifiedMethodWithTopCallers.values.flatten().distinct()

        /*untriggeredTargetHiddenHandlers.addAll(targetHandlers)*/
        val topCallerToModifiedMethods = HashMap<String, HashSet<String>>()
        for (entry in modifiedMethodWithTopCallers.entries) {
            entry.value.forEach {
                topCallerToModifiedMethods.putIfAbsent(it, HashSet())
                topCallerToModifiedMethods[it]!!.add(entry.key)
            }
        }

        WindowManager.instance.updatedModelWindows.filter { it.inputs.any { it.modifiedMethods.isNotEmpty() } }
            .forEach {
                if (it !is OutOfApp && it !is Launcher ) {
                    modifiedMethodsByWindow.putIfAbsent(it, HashSet())
                    val allUpdatedMethods = modifiedMethodsByWindow.get(it)!!
                    it.inputs.forEach {
                        if (it.modifiedMethods.isNotEmpty() ||
                            (it.eventHandlers.isNotEmpty() && it.eventHandlers.intersect(allTargetHandlers)
                                .isNotEmpty())
                        ) {
                            allUpdatedMethods.addAll(it.modifiedMethods.keys)
                            if (!notFullyExercisedTargetInputs.contains(it)) {
                                notFullyExercisedTargetInputs.add(it)
                                if (reuseBaseModel) {
                                    TargetInputReport.INSTANCE.targetIdentifiedByBaseModel.add(it)
                                } else {
                                    log.debug("Strange. Inspect!")
                                }
                            }
                        } else {
                            if (notFullyExercisedTargetInputs.contains(it)) {
                                notFullyExercisedTargetInputs.remove(it)
                                TargetInputReport.INSTANCE.targetRemovedByBaseModel.add(it)
                            }
                        }
                    }
                    targetHandlers.intersect(windowHandlersHashMap[it] ?: emptyList()).forEach {
                        val modifiedMethods = topCallerToModifiedMethods[it] ?: HashSet()
                        allUpdatedMethods.addAll(modifiedMethods)
                    }
                }
            }
        if (reuseBaseModel) {
            AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                it.modelVersion == ModelVersion.BASE
                        && it.window is OutOfApp
            }.forEach {
                it.initAction(this)
                it.abstractTransitions.forEach {
                    if (it.modifiedMethods.isNotEmpty()) {
                        modifiedMethodsByWindow.putIfAbsent(it.dest.window, HashSet())
                        modifiedMethodsByWindow[it.dest.window]!!.addAll(it.modifiedMethods.keys)
                    }
                }
            }

            AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.modelVersion == ModelVersion.BASE }
                .forEach { abSt ->
                    abSt.abstractTransitions.filter { !it.isImplicit }.forEach {
                        val inputs = abSt.getInputsByAbstractAction(it.abstractAction)
                        inputs.forEach { input ->
                            updateInputReachability(input, it)
                        }
                        if (it.source != it.dest && !it.dest.ignored)
                            dstg.updateAbstractActionEnability(abstractTransition = it,atua = this)
                        wtg.edges(it.source.window).filter { edge->
                            edge.destination?.data != it.dest.window
                                    && it.source.getInputsByAbstractAction(it.abstractAction).contains(edge.label.input)
                        }.forEach { edge ->
                            edge.label.disabled = true
                            wtg.remove(edge)
                        }
                    }
                }

            val transferTargetMethods = HashMap<Window, ArrayList<String>>()
            modifiedMethodsByWindow.forEach { window, methods ->
                   if (window is Dialog) {
                       window.ownerActivitys.forEach {
                           if (it is Activity) {
                               transferTargetMethods.putIfAbsent(it, ArrayList())
                               transferTargetMethods[it]!!.addAll(methods)
                           }
                       }
                   }
            }
            transferTargetMethods.forEach {
                modifiedMethodsByWindow.putIfAbsent(it.key, HashSet())
                modifiedMethodsByWindow[it.key]!!.addAll(it.value)
            }
            modifiedMethodsByWindow.entries.removeIf {
                it.key is Dialog && it.key.isRuntimeCreated
            }
            val newWindows = if (!reuseSameVersionModel)
                EWTGDiff.instance.windowDifferentSets["AdditionSet"]!! as AdditionSet<Window>
            else
                null
            val seenWindows =
                AbstractStateManager.INSTANCE.ABSTRACT_STATES.filterNot { it is VirtualAbstractState || it.modelVersion == ModelVersion.RUNNING }
                    .map { it.window }.distinct()
            val unseenWindows = WindowManager.instance.updatedModelWindows.filterNot {
                seenWindows.contains(it) || newWindows?.addedElements?.contains(it) ?: false
            }
           /* unseenWindows.forEach {
                if (modifiedMethodsByWindow.containsKey(it)) {
                    if (it !is OptionsMenu)
                        backupModifiedMethodsByWindow.put(it, modifiedMethodsByWindow[it]!!)
                    modifiedMethodsByWindow.remove(it)
                }
            }*/
            /*notFullyExercisedTargetInputs.removeIf {
                val toDelete = unseenWindows.contains(it.sourceWindow)
                if (toDelete)
                    backupNotFullyExercisedTargetInputs.add(it)
                toDelete
            }*/
            notFullyExercisedTargetInputs.removeIf {
                val toDelete = it.sourceWindow is Dialog
                if (toDelete)
                    backupNotFullyExercisedTargetInputs.add(it)
                toDelete
            }
            val seenWidgets = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filterNot { it is VirtualAbstractState }
                .map { it.EWTGWidgetMapping.values }.flatten().distinct()
            val newWidgets = if (!reuseSameVersionModel)
                (EWTGDiff.instance.widgetDifferentSets["AdditionSet"]!! as AdditionSet<EWTGWidget>).addedElements
            else
                null

            notFullyExercisedTargetInputs.removeIf {
                val toDelete = (it.widget != null
                        && !seenWidgets.contains(it.widget!!)
                        && !(newWidgets?.contains(it.widget!!) ?: false))
                if (toDelete)
                    backupNotFullyExercisedTargetInputs.add(it)
                toDelete
            }
           /* notFullyExercisedTargetInputs.removeIf {
                val toDelete =
                    (it.coveredMethods.isEmpty() && ModelHistoryInformation.INSTANCE.inputUsefulness.containsKey(it))
                if (toDelete)
                    backupNotFullyExercisedTargetInputs.add(it)
                toDelete
            }*/
            val uselessInputs =
                notFullyExercisedTargetInputs.filter { ModelHistoryInformation.INSTANCE.inputUsefulness.containsKey(it) && ModelHistoryInformation.INSTANCE.inputUsefulness[it]?.second == 0 }

        }

        modifiedMethodsByWindow.entries.removeIf {
            it.key is Launcher ||
                    it.key is OutOfApp || it.key.classType == "com.google.android.gms.signin.activity.SignInActivity"
        }

        modifiedMethodsByWindow.entries.removeIf { it.key is FakeWindow }
        modifiedMethodsByWindow.entries.removeIf { entry ->
            entry.key.inputs.all { it.modifiedMethods.isEmpty() }
                    && (windowHandlersHashMap[entry.key] == null
                    || (
                    windowHandlersHashMap[entry.key] != null
                            && windowHandlersHashMap[entry.key]!!.all { !targetHandlers.contains(it) }
                    ))
        }
        processOptionsMenusWindow()
        log.info("Before processed target inputs: $beforeProcessedTargetCount")
        log.info("After processed target inputs: ${notFullyExercisedTargetInputs.size}")
    }

    val backupModifiedMethodsByWindow = HashMap<Window, HashSet<String>>()
    val backupNotFullyExercisedTargetInputs = HashSet<Input>()

    private fun removeDuplicatedWidgets() {
        WindowManager.instance.updatedModelWindows.forEach { window ->
            val workingList = Stack<EWTGWidget>()
            window.widgets.filter { it.children.isEmpty() }.forEach {
                workingList.add(it)
            }
            val roots = HashSet<EWTGWidget>()
            while (workingList.isNotEmpty()) {
                val widget = workingList.pop()
                if (widget.parent != null) {
                    workingList.push(widget.parent)
                } else {
                    roots.add(widget)
                }
                if (widget.children.isNotEmpty()) {
                    val childrenSignatures = widget.children.map { Pair(it, it.generateSignature()) }
                    childrenSignatures.groupBy { it.second }.filter { it.value.size > 1 }.forEach { _, pairs ->
                        val keep = pairs.first().first
                        val removes = pairs.filter { it.first != keep }
                        removes.map { it.first }.forEach { removewidget ->
                            val relatedInputs = window.inputs.filter { it.widget == removewidget }
                            relatedInputs.forEach {
                                it.widget = keep
                            }
                            window.widgets.remove(removewidget)
                        }
                    }
                }
            }
            val rootSignatures = roots.map { Pair(it, it.generateSignature()) }
            rootSignatures.groupBy { it.second }.filter { it.value.size > 1 }.forEach { _, pairs ->
                val keep = pairs.first().first
                val removes = pairs.filter { it.first != keep }
                removes.map { it.first }.forEach { removewidget ->
                    val relatedInputs = window.inputs.filter { it.widget == removewidget }
                    relatedInputs.forEach {
                        it.widget = keep
                    }
                    window.widgets.remove(removewidget)
                }
            }
        }
    }

    private fun loadBaseModel() {
        AppModelLoader.loadModel(baseModelDir.resolve(appName), reuseSameVersionModel, this)
        ModelBackwardAdapter.instance.initialBaseAbstractStates.addAll(
            AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                it.modelVersion == ModelVersion.BASE
            }
        )
        ModelBackwardAdapter.instance.initialBaseAbstractStates.forEach {
            ModelBackwardAdapter.instance.initialBaseAbstractTransitions.addAll(it.abstractTransitions.filter { it.isExplicit() })
        }
        if (!reuseSameVersionModel) {
            val ewtgDiff = EWTGDiff.instance
            val ewtgDiffFile = getEWTGDiffFile(appName, resourceDir)
            if (ewtgDiffFile != null) {
                ewtgDiff.loadFromFile(ewtgDiffFile, this)
            }
        }
        AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.forEach { window, u ->
            u.values.forEach {
                it.computeHashCode(window)
            }
        }
        ModelBackwardAdapter.instance.keptBaseAbstractStates.addAll(
            AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                it.modelVersion == ModelVersion.BASE
            }
        )
        ModelBackwardAdapter.instance.keptBaseAbstractStates.forEach {
            ModelBackwardAdapter.instance.keptBaseAbstractTransitions.addAll(
                it.abstractTransitions.filter { it.isExplicit() })
        }

    }

    private fun processOptionsMenusWindow() {
        WindowManager.instance.updatedModelWindows.filter {
            it is OptionsMenu
        }.forEach { menus ->
            val activity =
                WindowManager.instance.updatedModelWindows.find { it is Activity && it.classType == menus.classType }
            if (activity != null) {
                wtg.mergeNode(menus, activity)
            }
        }
        WindowManager.instance.updatedModelWindows.removeIf { w ->
            if (w is OptionsMenu) {
                wtg.removeVertex(w)
                true
            } else false
        }
        modifiedMethodsByWindow.entries.removeIf { !WindowManager.instance.updatedModelWindows.contains(it.key) }
    }


    override suspend fun onContextUpdate(context: ExplorationContext<*, *, *>) {
        //this.join()
        mutex.lock()
        try {
            log.info("ATUAMF: Start OnContextUpdate")
            modifiedMethodsByWindow.entries.removeIf { it.key is Launcher || it.key is OptionsMenu || it.key is FakeWindow }
            val interactions = ArrayList<Interaction<Widget>>()
            val lastAction = context.getLastAction()
            if (lastAction.actionType != "Terminate") {
                if (lastAction.actionType.isQueueEnd()) {
                    val lastQueueStart = context.explorationTrace.getActions().last { it.actionType.isQueueStart() }
                    val lastQueueStartIndex = context.explorationTrace.getActions().lastIndexOf(lastQueueStart)
                    val lastLaunchAction = context.explorationTrace.getActions()
                        .last { it.actionType.isLaunchApp() || it.actionType == "ResetApp" }
                    val lastLauchActionIndex = context.explorationTrace.getActions().lastIndexOf(lastLaunchAction)
                    if (lastLauchActionIndex > lastQueueStartIndex) {
                        interactions.add(lastLaunchAction)
                    } else {
                        context.explorationTrace.getActions()
                            .takeLast(context.explorationTrace.getActions().lastIndex - lastQueueStartIndex + 1)
                            .filterNot { it.actionType.isQueueStart() || it.actionType.isQueueEnd() || it.actionType.isFetch() }
                            .let {
                                interactions.addAll(it)
                            }
                    }
                } else {
                    interactions.add(context.getLastAction())
                }
                if (interactions.any { it.actionType == "ResetApp" }) {
                    fromLaunch = true
//                    windowStack.clear()
                    abstractStateStack.clear()
                    guiStateStack.clear()
                    /*windowStack.push(Launcher.getOrCreateNode())
                    abstractStateStack.push(AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.isHomeScreen }!!)
                    guiStateStack.push(stateList.findLast { it.isHomeScreen })*/
//                abstractStateStack.push(Pair(AbstractStateManager.instance.ABSTRACT_STATES.find { it.window is Launcher}!!,stateList.findLast { it.isHomeScreen } ))
                } else {
                    fromLaunch = false
                }
                isModelUpdated = false
                val prevState = context.getState(context.getLastAction().prevState) ?: context.model.emptyState
                val newState = context.getCurrentState()
                if (!firstReachingStateUUID.containsKey(newState.uid)) {
                    firstReachingStateUUID.put(newState.uid,context.getLastAction().actionId)
                }
                if (prevState == context.model.emptyState) {

                } else {
                    appPrevState = prevState
                    if (prevState.isHomeScreen) {
                        if (retrieveScreenDimension(prevState)) {
                            AbstractStateManager.INSTANCE.ABSTRACT_STATES.removeIf {
                                it !is VirtualAbstractState && !it.loadedFromModel
                            }
                        }
                    }
                    if (!prevState.isHomeScreen && prevState.widgets.find { it.packageName == appName } != null) {
                        //getCurrentEventCoverage()
                        //val currentCov = statementMF!!.getCurrentCoverage()
                        val currentCov = statementMF!!.getCurrentMethodCoverage()
                        if (currentCov > lastMethodCoverage) {
                            methodCovFromLastChangeCount = 0
                            lastMethodCoverage = currentCov
                        } else {
                            methodCovFromLastChangeCount += 1
                        }
                        //val currentModifiedMethodStmtCov = statementMF!!.getCurrentModifiedMethodStatementCoverage()
                        val currentUpdatedMethodCov = statementMF!!.getCurrentModifiedMethodCoverage()
                        if (currentUpdatedMethodCov > lastUpdatedMethodCoverage) {
                            updateMethodCovFromLastChangeCount = 0
                            lastUpdatedMethodCoverage = currentUpdatedMethodCov
                        } else {
                            updateMethodCovFromLastChangeCount += 1
                        }
                        val currentUpdatedStmtCov = statementMF!!.getCurrentModifiedMethodStatementCoverage()
                        if (currentUpdatedStmtCov > lastUpdatedStatementCoverage) {
                            updateStmtCovFromLastChangeCount = 0
                            lastUpdatedStatementCoverage = currentUpdatedStmtCov
                        } else {
                            updateStmtCovFromLastChangeCount += 1
                        }
                    }
                }

                if (newState != context.model.emptyState) {
                    if (newState.isAppHasStoppedDialogBox) {
                        org.atua.modelFeatures.ATUAMF.Companion.log.debug("Encountering Crash state.")
                    }
                    if (newState.isHomeScreen) {
                        if (retrieveScreenDimension(newState)) {
                            AbstractStateManager.INSTANCE.ABSTRACT_STATES.removeIf {
                                it !is VirtualAbstractState
                                        && !it.loadedFromModel
                            }
                        }
                    }
//                val notMarkedAsOccupied = newState.widgets.filter {
//                    it.metaInfo.contains("markedAsOccupied = false")
//                }
                    currentRotation = computeRotation()
                    lastExecutedTransition = null
                    updateAppModel(prevState, newState, interactions, context)
                    //validateModel(newState)
                } /*else {
                    val newState2 = prevState
                    currentRotation = computeRotation()
                    lastExecutedTransition = null
                    updateAppModel(prevState, newState2, interactions, context)
                }*/
                isRandomExploration = false
            }
        } finally {
            mutex.unlock()
        }
    }


    private fun retrieveScreenDimension(state: State<*>): Boolean {
        //get fullscreen app resolution
        val rotation = computeRotation()
        if (rotation == org.atua.modelFeatures.Rotation.PORTRAIT && portraitScreenSurface == Rectangle.empty()) {
            val fullDimension = Helper.computeGuiTreeDimension(state)
            val fullVisbleDimension = Helper.computeGuiTreeVisibleDimension(state)
            portraitScreenSurface = fullDimension
            portraitVisibleScreenSurface = fullVisbleDimension
            landscapeScreenSurface =
                Rectangle.create(fullDimension.topY, fullDimension.leftX, fullDimension.bottomY, fullDimension.rightX)
            landscapeVisibleScreenSurface = Rectangle.create(
                fullVisbleDimension.topY,
                fullVisbleDimension.leftX,
                fullVisbleDimension.bottomY,
                fullVisbleDimension.rightX
            )
            org.atua.modelFeatures.ATUAMF.Companion.log.debug("Screen resolution: $portraitScreenSurface")
            return true
        } else if (rotation == org.atua.modelFeatures.Rotation.LANDSCAPE && landscapeScreenSurface == Rectangle.empty()) {
            val fullDimension = Helper.computeGuiTreeDimension(state)
            val fullVisbleDimension = Helper.computeGuiTreeVisibleDimension(state)
            landscapeScreenSurface = fullDimension
            landscapeVisibleScreenSurface = fullVisbleDimension
            portraitScreenSurface =
                Rectangle.create(fullDimension.topY, fullDimension.leftX, fullDimension.bottomY, fullDimension.rightX)
            portraitVisibleScreenSurface = Rectangle.create(
                fullVisbleDimension.topY,
                fullVisbleDimension.leftX,
                fullVisbleDimension.bottomY,
                fullVisbleDimension.rightX
            )
            org.atua.modelFeatures.ATUAMF.Companion.log.debug("Screen resolution: $portraitScreenSurface")
            return true
        }
        return false
    }

    var isActivityResult = false

    private fun recomputeWindowStack() {
        val currentTraceId = traceId
//        windowStack.clear()
        abstractStateStack.clear()
        for (i in 0..guiStateStack.size-2) {
            val prevState = guiStateStack[i]
            val destState = guiStateStack[i+1]
            val prevAbstractState = getAbstractState(prevState)
            val destAbstractState = getAbstractState(destState)
            if (destAbstractState == null)
                continue
            val isLaunch = if (i==0)
                true
            else
                false
            updateAppStateStack(prevAbstractState,prevState,destAbstractState,destState,isLaunch)
        }
    }


    private fun updateAppStateStack(
        prevAbstractState: AbstractState?,
        prevState: State<*>,
        currentAbstractState: AbstractState,
        currentState: State<*>,
        isLaunch: Boolean
    ) {

        if (isLaunch) {
//            windowStack.push(Launcher.getOrCreateNode())
            val homeScreenState = stateList.findLast { it.isHomeScreen }
            if (homeScreenState != null) {
//                stateList.add(homeScreenState)
                abstractStateStack.push(getAbstractState(homeScreenState)!!)
            } else {
                abstractStateStack.push(AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.window is Launcher }!!)
            }
        } else if (prevAbstractState != null) {
            val topWindow = abstractStateStack.peek().window
            if (topWindow == currentAbstractState) {
                // Stay in the same window
                // Just remove any state similar to the current state
                abstractStateStack.removeIf {
                    it.isSimlarAbstractState(currentAbstractState,0.8)
                }
            } else {
                if (abstractStateStack.map { it.window }.contains(currentAbstractState.window)
                    && abstractStateStack.size > 1 ) {
                    // Return to one of the previous windows
                    while(abstractStateStack.peek().window!=currentAbstractState.window) {
                        abstractStateStack.pop()
                        if (abstractStateStack.isEmpty())
                            break
                    }
                    if (abstractStateStack.isNotEmpty()) {
                        abstractStateStack.removeIf {
                            it.isSimlarAbstractState(currentAbstractState,0.8)
                                    || it.window is Dialog
                        }
                    }
                }
            }
        }
        abstractStateStack.push(currentAbstractState)
    }

    private fun registerPrevWindowState(
        prevState: State<*>,
        currentState: State<*>,
        lastInteraction: Interaction<Widget>
    ) {

        val prevprevAppState: AbstractState
        val prevAppState = getAbstractState(prevState)
        if (prevAppState == null) {
            return
        }
        val currentAppState = getAbstractState(currentState)!!
        if (abstractStateStack.size<=1)
            return
        /*if (abstractStateStack.contains(prevAppState)
            && abstractStateStack.indexOf(prevAppState)>0) {
            val i = abstractStateStack.indexOf(prevAppState)
            prevprevAppState = abstractStateStack[i-1]
        } else {
            prevprevAppState = abstractStateStack.peek()
        }*/
        prevprevAppState = abstractStateStack.peek()
        val prevWindow: Window = prevprevAppState.window
        if (prevWindow is Launcher) {
            val prevLaucherState = stateList.findLast { it.isHomeScreen }
            if (prevLaucherState != null) {
                interactionPrevWindowStateMapping.put(lastInteraction, prevLaucherState)
            }
            return
        }
        var tempCandidate: State<*>? = null
        var flag = false
        for (i in transitionId downTo 1) {
            val traveredInteraction = tracingInteractionsMap.get(Pair(traceId, i))
            if (traveredInteraction == null)
                throw Exception()
            val backwardTraversedState = stateList.find { it.stateId == traveredInteraction.last().prevState }!!
            val backwardTraversedAppState = getAbstractState(backwardTraversedState)!!
            if (!flag && backwardTraversedAppState == prevprevAppState) {
                flag = true
            }
            if (flag && abstractStateStack.contains(backwardTraversedAppState)) {
                if (backwardTraversedAppState.window != currentAppState.window
                    && !backwardTraversedAppState.isOpeningKeyboard
                    && !backwardTraversedAppState.isOpeningMenus
                ) {
                    tempCandidate = null
                    interactionPrevWindowStateMapping[lastInteraction] = backwardTraversedState
                    break
                }
                if ( backwardTraversedAppState.window != currentAppState.window
                    && !backwardTraversedAppState.isOpeningMenus
                    && backwardTraversedAppState.isOpeningKeyboard
                ) {
                    tempCandidate = backwardTraversedState
                }
            }
            if (i == 1 && tempCandidate == null ) {
                val lastHomeScreen = stateList.findLast { it.isHomeScreen }
                if (lastHomeScreen!=null)
                    interactionPrevWindowStateMapping[lastInteraction] = lastHomeScreen
            }
        }
        if (tempCandidate != null ) {
            interactionPrevWindowStateMapping[lastInteraction] = tempCandidate
        }
    }

    private fun computeRotation(): org.atua.modelFeatures.Rotation {

        var rotation = 0
        runBlocking {
            rotation = getDeviceRotation()
        }
        if (rotation == 0 || rotation == 2)
            return org.atua.modelFeatures.Rotation.PORTRAIT
        return org.atua.modelFeatures.Rotation.LANDSCAPE
    }

    val guiInteractionList = ArrayList<Interaction<Widget>>()

    private fun deriveAbstractInteraction(
        interactions: ArrayList<Interaction<Widget>>,
        prevState: State<*>,
        currentState: State<*>,
        statementCovered: Boolean
    ) {
        log.info("Computing Abstract Interaction.")
        extraWebViewAbstractTransition = null
        if (interactions.isEmpty())
            return
        val prevAbstractState = AbstractStateManager.INSTANCE.getAbstractState(prevState)
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
        if (prevAbstractState == null)
            return
        val prevWindowAbstractState: AbstractState? = getPrevWindowAbstractState(traceId, transitionId)
        if (prevWindowAbstractState == null)
            log.debug("Cannot get prevWindowAbstractState")
        if (interactions.size == 1) {
            val interaction = interactions.first()
            if (!prevAbstractState.guiStates.any { it.stateId == interaction.prevState })
                throw Exception("Missing GUI States for interaction")
            if (!currentAbstractState.guiStates.any { it.stateId == interaction.resState })
                throw Exception("Missing GUI States for interaction")
            deriveSingleInteraction(prevAbstractState, interaction, currentAbstractState, prevState, currentState)
        } else {
            val actionType = AbstractActionType.ACTION_QUEUE
            val data = interactions
            val abstractAction = AbstractAction.getOrCreateAbstractAction(
                actionType = actionType,
                attributeValuationMap = null,
                extra = interactions,
                window = prevAbstractState.window
            )
            val abstractTransition = AbstractTransition(
                abstractAction = abstractAction,
                interactions = HashSet(),
                isImplicit = false,
                /*prevWindow = windowStack.peek(),*/
                data = data,
                source = prevAbstractState,
                dest = currentAbstractState
            )
            /*if (prevWindowAbstractState != null)
                abstractTransition.dependentAbstractStates.add(prevWindowAbstractState)*/
            /*if (abstractTransition.dependentAbstractStates.map { it.window }.contains(currentAbstractState.window)
                && abstractTransition.guardEnabled == false
            ) {
                abstractTransition.guardEnabled = true
            }*/
            abstractTransition.computeGuaranteedAVMs()
            dstg.add(prevAbstractState, currentAbstractState, abstractTransition)
            lastExecutedTransition = abstractTransition
        }
        if (lastExecutedTransition == null) {
            log.info("Not processed interaction: ${interactions.toString()}")
            return
        }


        if (statementCovered || currentState != prevState) {
            transitionId++
            interactionsTracingMap[interactions] = Pair(traceId, transitionId)
            tracingInteractionsMap[Pair(traceId, transitionId)] = interactions
            lastExecutedTransition!!.tracing.add(Pair(traceId, transitionId))

            if (lastExecutedTransition!!.abstractAction.actionType == AbstractActionType.WAIT){
                if (transitionId>1) {
                    // Reconnect previous abstract transitions
                    var previousNotWaitTransition: AbstractTransition? = null
                    var prevTransitionId = transitionId-1
                    while (prevTransitionId>1) {
                        val prevInteraction = tracingInteractionsMap[Pair(traceId,prevTransitionId)]?.first()
                        prevTransitionId--
                        if (prevInteraction == null)
                            break
                        val prevAbstractTransition = AbstractTransition.interaction_AbstractTransitionMapping[prevInteraction]
                        if (prevAbstractTransition == null)
                            break
                        if (prevAbstractTransition.abstractAction.actionType != AbstractActionType.WAIT) {
                            previousNotWaitTransition = prevAbstractTransition
                            break
                        }
                    }
                    if (previousNotWaitTransition != null) {
                        val newAbstractTransition = AbstractTransition(
                            source = previousNotWaitTransition.source,
                            dest = lastExecutedTransition!!.dest,
                            abstractAction = previousNotWaitTransition.abstractAction,
                            interactions = HashSet(),
                            data = previousNotWaitTransition.data,
                            fromWTG = false,
                            modelVersion = ModelVersion.RUNNING,
                            isImplicit = false
                        )
                        val prevInteraction = previousNotWaitTransition.interactions.last()
                        val newInteraction = Interaction<Widget>(
                            actionType = prevInteraction.actionType,
                            data = prevInteraction.data,
                            actionId = prevInteraction.actionId,
                            deviceLogs = prevInteraction.deviceLogs.union(interactions.first().deviceLogs).toList(),
                            startTimestamp = prevInteraction.startTimestamp,
                            endTimestamp = interactions.first().endTimestamp,
                            exception = prevInteraction.exception.plus(interactions.first().exception),
                            meta = prevInteraction.meta.plus(interactions.first().meta),
                            prevState = prevInteraction.prevState,
                            resState = interactions.first().resState,
                            successful = true,
                            targetWidget = prevInteraction.targetWidget
                        )
                        newAbstractTransition.interactions.add(newInteraction)
                        newAbstractTransition.interactions.forEach {
                            AbstractTransition.interaction_AbstractTransitionMapping.put(it,newAbstractTransition)
                        }

                        newAbstractTransition.copyPotentialInfoFrom(previousNotWaitTransition)
                        newAbstractTransition.requireWaitAction = true
                        previousNotWaitTransition.source.abstractTransitions.remove(previousNotWaitTransition)
                        lastExecutedTransition!!.source.abstractTransitions.remove(lastExecutedTransition!!)
                        lastExecutedTransition = newAbstractTransition
                    }
                }
            }
                if (lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.RESET_APP) {
                lastExecutedTransition!!.computeMemoryBasedGuards(currentState,traceId,transitionId,this)
                lastExecutedTransition!!.markNondeterministicTransitions(this)
                lastExecutedTransition!!.activated = true
                dstg.updateAbstractActionEnability(lastExecutedTransition!!, this)
                AbstractStateManager.INSTANCE.updateImplicitAppTransitions(prevAbstractState,lastExecutedTransition!!)
                val sourceAbstractState = lastExecutedTransition!!.source
                val destAbstractState = lastExecutedTransition!!.dest
                if ( !lastExecutedTransition!!.abstractAction.isActionQueue()
                    && !lastExecutedTransition!!.abstractAction.isLaunchOrReset()
                ) {
                    if (!sourceAbstractState.isAbstractActionMappedWithInputs(lastExecutedTransition!!.abstractAction)
                        ) {
                        Input.createInputFromAbstractInteraction(
                            sourceAbstractState,
                            destAbstractState,
                            lastExecutedTransition!!,
                            interactions.first(),
                            wtg
                        )

                    }
                    val inputs = sourceAbstractState.getInputsByAbstractAction(lastExecutedTransition!!.abstractAction)
                    val prevWindows = lastExecutedTransition!!.dependentAbstractStates.map { it.window }
                    inputs.forEach { input->
                        val windowTransition = Input.createWindowTransition(
                            prevWindows,
                            wtg,
                            lastExecutedTransition!!.source,
                            lastExecutedTransition!!.dest,
                            input
                        )
                        AbstractStateManager.INSTANCE.updateEWTGAbstractTranstions(windowTransition)
                    }

                }
            }
        }
        log.info("Computing Abstract Interaction. - DONE")

    }


    fun getPrevSameWindowAbstractState(
        currentState: State<*>,
        traceId: Int,
        transitionId: Int,
        isReturnToPrevWindow: Boolean
    ): List<AbstractState> {
        val prevSameWindowAbstractStates = ArrayList<AbstractState>()
        val currentAppState = getAbstractState(currentState)!!
        val currentWindow = currentAppState.window
        var ignoreWindow = true
        for (i in transitionId-1 downTo 1) {
            val traveredInteraction = tracingInteractionsMap.get(Pair(traceId, i))
            if (traveredInteraction == null)
                throw Exception()
            val prevWindowState = stateList.find { it.stateId == traveredInteraction.last().prevState }!!
            val prevWindowAbstractState = getAbstractState(prevWindowState)!!
            if (prevWindowAbstractState.window != currentWindow) {
                if (isReturnToPrevWindow)
                    if (ignoreWindow)
                        continue
                    else
                        break
                else
                    break
            } else {
                if (isReturnToPrevWindow)
                    ignoreWindow = false
            }
            if (!prevWindowAbstractState.isOpeningKeyboard || currentAppState.isOpeningKeyboard)
                prevSameWindowAbstractStates.add(prevWindowAbstractState)
        }
        return prevSameWindowAbstractStates
    }

    private fun deriveSingleInteraction(
        prevAbstractState: AbstractState,
        interaction: Interaction<Widget>,
        currentAbstractState: AbstractState,
        prevState: State<*>,
        currentState: State<*>
    ) {
        if (!prevAbstractState.guiStates.any { it.stateId == interaction.prevState }) {
            log.debug("Prev Abstract State does not contain interaction's prev state.")
            log.debug("Abstract state: " + prevAbstractState)
            log.debug("Gui state: " + interaction.prevState)
        }
        if (!currentAbstractState.guiStates.any { it.stateId == interaction.resState }) {
            log.debug("Current Abstract State does not contain interaction' res state.")
            log.debug("Abstract state: " + currentAbstractState)
            log.debug("Gui state: " + interaction.resState)
        }
        /*val prevWindowAbstractState = if (!fromLaunch) {
            if (prevWindowStateMapping.containsKey(prevState))
                getAbstractState(prevWindowStateMapping.get(prevState)!!)
            else
                null
        } else
            null*/
        val prevWindowAbstractState: AbstractState? = getPrevWindowAbstractState(traceId, transitionId)

        if (isRecentPressMenu) {
            if (prevAbstractState != currentAbstractState) {
                if (prevAbstractState.hasOptionsMenu)
                    currentAbstractState.hasOptionsMenu = false
                else
                    currentAbstractState.hasOptionsMenu = true
            } else {
                prevAbstractState.hasOptionsMenu = false
            }
            isRecentPressMenu = false
        }
        val actionType: AbstractActionType = AbstractAction.normalizeActionType(interaction, prevState)
        val actionData =
            AbstractAction.computeAbstractActionExtraData(actionType, interaction, prevState, prevAbstractState, this)
        val interactionData = AbstractTransition.computeAbstractTransitionData(
            actionType,
            interaction,
            prevState,
            prevAbstractState,
            this
        )
        when (actionType) {
            AbstractActionType.LAUNCH_APP -> {
                AbstractStateManager.INSTANCE.launchStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH] =
                    currentState
                if (AbstractStateManager.INSTANCE.launchStates[AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH] == null) {
                    AbstractStateManager.INSTANCE.launchStates[AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH] =
                        currentState
                    currentAbstractState.isInitalState = true
                }
            }
            AbstractActionType.RESET_APP -> {
                AbstractStateManager.INSTANCE.launchStates[AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH] =
                    currentState
                currentAbstractState.isInitalState = true
            }
            else -> {
                currentAbstractState.isInitalState = false
            }
        }
        if (interaction.targetWidget == null) {
            val allAbstractTransitions = dstg.edges(prevAbstractState)
            if (actionType == AbstractActionType.RESET_APP || actionType == AbstractActionType.LAUNCH_APP) {
                if (actionType == AbstractActionType.RESET_APP)
                    setNewTrace()
                deriveLaunchOrResetInteraction(
                    prevState,
                    allAbstractTransitions,
                    actionType,
                    null,
                    currentAbstractState,
                    interaction,
                    prevAbstractState
                )
            } else {
                deriveNonLaunchAndResetNullTargetInteraction(
                    prevState,
                    allAbstractTransitions,
                    actionType,
                    actionData,
                    interactionData,
                    currentAbstractState,
                    interaction,
                    prevAbstractState,
                    prevWindowAbstractState
                )
            }
        } else {
            actionCount.updateWidgetActionCounter(prevAbstractState, prevState, interaction)
            val widgetGroup = prevAbstractState.getAttributeValuationSet(interaction.targetWidget!!, prevState, this)
            if (widgetGroup != null) {
                deriveWidgetInteraction(
                    prevAbstractState,
                    actionType,
                    widgetGroup,
                    actionData,
                    currentAbstractState,
                    interaction,
                    interactionData,
                    prevWindowAbstractState,
                    prevState
                )
                if (Helper.hasParentWithType(interaction.targetWidget!!, prevState, "WebView")) {
                    val tmpLastExecutedTransition = lastExecutedTransition
                    deriveWebViewInteraction(
                        interaction,
                        prevState,
                        prevAbstractState,
                        actionType,
                        actionData,
                        currentAbstractState,
                        interactionData,
                        prevWindowAbstractState
                    )
                    extraWebViewAbstractTransition = lastExecutedTransition
                    lastExecutedTransition = tmpLastExecutedTransition
                }
            } else {
                if (Helper.hasParentWithType(interaction.targetWidget!!, prevState, "WebView")) {
                    deriveWebViewInteraction(
                        interaction,
                        prevState,
                        prevAbstractState,
                        actionType,
                        actionData,
                        currentAbstractState,
                        interactionData,
                        prevWindowAbstractState
                    )
                } else if (actionType == AbstractActionType.RANDOM_KEYBOARD) {
                    deriveKeyboardInteraction(
                        prevAbstractState,
                        actionType,
                        actionData,
                        interactionData,
                        currentAbstractState,
                        interaction,
                        prevState,
                        prevWindowAbstractState
                    )
                } else {
                    val underviceAction = AbstractActionType.UNKNOWN
                    createNewAbstractTransition(
                        underviceAction,
                        interaction,
                        prevState,
                        prevAbstractState,
                        null,
                        interaction,
                        currentAbstractState,
                        prevWindowAbstractState
                    )
                    org.atua.modelFeatures.ATUAMF.Companion.log.debug("Cannot find the target widget's AVM")
                    prevAbstractState.getAttributeValuationSet(interaction.targetWidget!!, prevState, this)
                }
            }
        }
        if (lastExecutedTransition != null) {
            if (!lastExecutedTransition!!.source.guiStates.any { it.stateId == interaction.prevState })
                throw Exception("Missing GUI States for interaction")
            if (!lastExecutedTransition!!.dest.guiStates.any { it.stateId == interaction.resState })
                throw Exception("Missing GUI States for interaction")
//            updateActionScore(currentState, prevState, interaction)
        } else {
            org.atua.modelFeatures.ATUAMF.Companion.log.warn("No abstract transition derived")
        }
    }

    fun getPrevWindowAbstractState(traceId: Int, transitionId: Int): AbstractState? {
        val prevWindowAbstractState: AbstractState?
        if (transitionId < 1) {
            prevWindowAbstractState = null
        } else {
            val traveredInteraction = tracingInteractionsMap.get(Pair(traceId, transitionId))
            if (traveredInteraction == null)
                throw Exception()
            if (!interactionPrevWindowStateMapping.containsKey(traveredInteraction.last())) {
                prevWindowAbstractState = null
            } else {
                val prevWindowState = interactionPrevWindowStateMapping.get(traveredInteraction.last())!!
                prevWindowAbstractState = getAbstractState(prevWindowState)
            }
        }
        return prevWindowAbstractState
    }

    private fun deriveKeyboardInteraction(
        prevAbstractState: AbstractState,
        actionType: AbstractActionType,
        actionData: Any?,
        interactionData: Any?,
        currentAbstractState: AbstractState,
        interaction: Interaction<Widget>,
        prevState: State<*>,
        prevWindowAbstractState: AbstractState?
    ) {
        val explicitInteractions = prevAbstractState.abstractTransitions.filter { it.isImplicit == false }
        val existingTransition = AbstractTransition.findExistingAbstractTransitions(
            explicitInteractions,
            AbstractAction.getOrCreateAbstractAction(
                actionType = actionType,
                attributeValuationMap = null,
                extra = actionData,
                window = prevAbstractState.window
            ),
            prevAbstractState,
            currentAbstractState
        )
        if (existingTransition != null) {
            lastExecutedTransition = existingTransition
            updateExistingAbstractTransition(existingTransition, interaction, interactionData, prevWindowAbstractState)
        } else {
            //No recored abstract interaction before
            //Or the abstractInteraction is implicit
            //Record new AbstractInteraction
            createNewAbstractTransition(
                actionType,
                interaction,
                prevState,
                prevAbstractState,
                null,
                interactionData,
                currentAbstractState,
                prevWindowAbstractState
            )
        }
    }

    private fun deriveWebViewInteraction(
        interaction: Interaction<Widget>,
        prevState: State<*>,
        prevAbstractState: AbstractState,
        actionType: AbstractActionType,
        actionData: Any?,
        currentAbstractState: AbstractState,
        interactionData: Any?,
        prevWindowAbstractState: AbstractState?
    ) {
        val webViewWidget = Helper.tryGetParentHavingClassName(interaction.targetWidget!!, prevState, "WebView")
        if (webViewWidget != null) {
            val webViewActionType: AbstractActionType = when (actionType) {
                AbstractActionType.CLICK -> AbstractActionType.ITEM_CLICK
                AbstractActionType.LONGCLICK -> AbstractActionType.ITEM_LONGCLICK
                else -> actionType
            }
            val avm = prevAbstractState.getAttributeValuationSet(webViewWidget, prevState, this)
            if (avm == null) {
                org.atua.modelFeatures.ATUAMF.Companion.log.debug("Cannot find WebView's AVM")
            } else {
                val explicitInteractions = prevAbstractState.abstractTransitions.filter { it.isImplicit == false }
                val existingTransition = AbstractTransition.findExistingAbstractTransitions(
                    abstractTransitionSet = explicitInteractions,
                    abstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = webViewActionType,
                        attributeValuationMap = avm,
                        extra = actionData,
                        window = prevAbstractState.window
                    ),
                    source = prevAbstractState,
                    dest = currentAbstractState
                )
                if (existingTransition != null) {
                    lastExecutedTransition = existingTransition
                    updateExistingAbstractTransition(
                        existingTransition,
                        interaction,
                        interactionData,
                        prevWindowAbstractState
                    )
                } else {
                    //No recored abstract interaction before
                    //Or the abstractInteraction is implicit
                    //Record new AbstractInteraction
                    createNewAbstractTransition(
                        webViewActionType,
                        interaction,
                        prevState,
                        prevAbstractState,
                        avm,
                        interactionData,
                        currentAbstractState,
                        prevWindowAbstractState
                    )
                }
                val exisitingImplicitTransitions = prevAbstractState.abstractTransitions.filter {
                    it.abstractAction == lastExecutedTransition!!.abstractAction
                            /*&& it.prevWindow == abstractTransition.prevWindow*/
                            && (
                            it.dependentAbstractStates.intersect(lastExecutedTransition!!.dependentAbstractStates)
                                .isNotEmpty()
                                    || it.guardEnabled == false
                                    || it.dependentAbstractStates.isEmpty()
                            )
                            /*&& (it.userInputs.intersect(abstractTransition.userInputs).isNotEmpty()
                            || it.userInputs.isEmpty() || abstractTransition.userInputs.isEmpty())*/
                            && it.isImplicit
                }
                exisitingImplicitTransitions.forEach { abTransition ->
                    val edge = dstg.edge(abTransition.source, abTransition.dest, abTransition)
                    if (edge != null) {
                        dstg.remove(edge)
                    }
                    prevAbstractState.abstractTransitions.remove(abTransition)
                }

            }
        }

    }

    private fun deriveWidgetInteraction(
        prevAbstractState: AbstractState,
        actionType: AbstractActionType,
        widgetGroup: AttributeValuationMap?,
        actionData: Any?,
        currentAbstractState: AbstractState,
        interaction: Interaction<Widget>,
        interactionData: Any?,
        prevWindowAbstractState: AbstractState?,
        prevState: State<*>
    ) {
        val explicitInteractions = prevAbstractState.abstractTransitions.filter { it.isImplicit == false }

        val existingTransition = AbstractTransition.findExistingAbstractTransitions(
            explicitInteractions,
            AbstractAction.getOrCreateAbstractAction(
                actionType = actionType,
                attributeValuationMap = widgetGroup,
                extra = actionData,
                window = prevAbstractState.window
            ),
            prevAbstractState,
            currentAbstractState
        )
        if (existingTransition != null) {
            lastExecutedTransition = existingTransition
            updateExistingAbstractTransition(
                existingTransition, interaction, interactionData, prevWindowAbstractState
            )
        } else {
            //No recored abstract interaction before
            //Or the abstractInteraction is implicit
            //Record new AbstractInteraction
            createNewAbstractTransition(
                actionType,
                interaction,
                prevState,
                prevAbstractState,
                widgetGroup,
                interactionData,
                currentAbstractState,
                prevWindowAbstractState
            )
        }

        val exisitingImplicitTransitions = prevAbstractState.abstractTransitions.filter {
            it.abstractAction == lastExecutedTransition!!.abstractAction
                    /*&& it.prevWindow == abstractTransition.prevWindow*/
                    && (
                    it.dependentAbstractStates.intersect(lastExecutedTransition!!.dependentAbstractStates).isNotEmpty()
                            || it.guardEnabled == false
                            || it.dependentAbstractStates.isEmpty()
                    )
                    && (it.userInputs.intersect(lastExecutedTransition!!.userInputs).isNotEmpty()
                    || it.userInputs.isEmpty() || lastExecutedTransition!!.userInputs.isEmpty())
                    && it.interactions.isEmpty()
        }

        exisitingImplicitTransitions.forEach { abTransition ->
            val edge = dstg.edge(abTransition.source, abTransition.dest, abTransition)
            if (edge != null) {
                dstg.remove(edge)
            }
            prevAbstractState.abstractTransitions.remove(abTransition)
            if (abTransition.modelVersion == ModelVersion.BASE) {
//                dstg.removeAbstractActionEnabiblity(abTransition,this)
                val backwardTransitions = ModelBackwardAdapter.instance.backwardEquivalentAbstractTransitionMapping.get(abTransition)
                backwardTransitions?.forEach { abstractTransition ->
                    abstractTransition.activated = false
                }
            }
        }
    }

    private fun updateExistingAbstractTransition(
        existingTransition: AbstractTransition,
        interaction: Interaction<Widget>,
        interactionData: Any?,
        prevWindowAbstractState: AbstractState?
    ) {
        existingTransition.interactions.add(interaction)
        AbstractTransition.interaction_AbstractTransitionMapping.put(interaction,existingTransition)
        existingTransition.isImplicit = false
        existingTransition.activated = true
        if (interactionData != null) {
            existingTransition.data = interactionData
        }

        /*if (prevWindowAbstractState != null && !existingTransition.dependentAbstractStates.contains(
                prevWindowAbstractState
            )
        ) {
            existingTransition.dependentAbstractStates.add(prevWindowAbstractState)
            existingTransition.computeGuaranteedAVMs()
        }*/
    }

    private fun setNewTrace() {
        traceId++
        transitionId = 0
    }

    var newWidgetScore = 1000.00
    var newActivityScore = 5000.00
    var coverageIncreaseScore = 1000.00
    private fun updateActionScore(currentState: State<*>, prevState: State<*>, interaction: Interaction<Widget>) {
        //newWidgetScore+=10
        val currentAbstractState = getAbstractState(currentState)
        if (currentAbstractState == null) {
            return
        }
        var reward = 0.0
        if (!windowVisitCount.containsKey(currentAbstractState.window)) {
            return
        }
        if (windowVisitCount[currentAbstractState.window] == 1 && currentAbstractState.window is Activity) {
            reward += newActivityScore
            newActivityScore *= 1.1
        }

        /*if (coverageIncreased > 0) {
            reward+=coverageIncreaseScore
            coverageIncreaseScore*=1.1
        }*/
        //init score for new state
        var newWidgetCount = 0
        val actionableWidgets =
            Helper.getActionableWidgetsWithoutKeyboard(currentState).filter { !Helper.isUserLikeInput(it) }
        if (eContext!!.explorationCanMoveOn()) {
            currentAbstractState.getAvailableInputs().filter { it.eventType != EventType.resetApp }.forEach { input ->
                if (!actionScore2.containsKey(input)) {
                    actionScore2[input] = newWidgetScore
                }
            }
            actionableWidgets.groupBy { it.uid }.forEach { uid, w ->
                val actions = Helper.getScoredAvailableActionsForWidget(w.first(), currentState, 0, false)
                actions.forEach { action ->
                    val widget_action = Triple(uid, action.first, action.second)
                    actionScore.putIfAbsent(widget_action, HashMap())
                    /*if (unexercisedWidgetCnt.contains(w)) {
                    reward += 10
                }*/
                    if (actionScore[widget_action]!!.values.isNotEmpty()) {
                        val avgScore = actionScore[widget_action]!!.values.average()
                        actionScore[widget_action]!!.putIfAbsent(currentAbstractState.window, avgScore)
                    } else {
                        val score = if (normalizeActionType(action.first) == AbstractActionType.LONGCLICK) {
                            0.0
                        } else {
                            newWidgetScore
                        }
                        actionScore[widget_action]!!.putIfAbsent(currentAbstractState.window, score)
                        if (!currentAbstractState.isOutOfApplication) {
                            newWidgetCount++
                        }
                    }
                }
            }
            val pressBackAction = Triple<UUID?, String, String>(null, "PressBack", "")
            val pressMenuAction = Triple<UUID?, String, String>(null, "PressMenu", "")
            val minimizeMaximizeAction = Triple<UUID?, String, String>(null, "MinimizeMaximize", "")

            actionScore.putIfAbsent(pressBackAction, HashMap())
            //actionScore.putIfAbsent(pressMenuAction, HashMap())
            actionScore[pressBackAction]!!.putIfAbsent(currentAbstractState.window, newWidgetScore)

            //actionScore[pressMenuAction]!!.putIfAbsent(currentAbstractState.window, newWidgetScore)

        }

        if (newWidgetCount == 0)
            reward -= 1000
        else
            reward += 1000
/*        if (stateVisitCount[currentState]!! == 1 && !currentState.widgets.any { it.isKeyboard }) {
            // this is a new state
            reward += newWidgetScore
        }*/
        /*if (!isScoreAction(interaction, prevState)) {
            return
        }*/
        val widget = interaction.targetWidget

        val prevAbstractState = getAbstractState(prevState)
        if (prevAbstractState == null) {
            return
        }
        if (currentAbstractState.isRequestRuntimePermissionDialogBox)
            return
        val normalizeActionType = AbstractAction.normalizeActionType(interaction, prevState)
        if (normalizeActionType == AbstractActionType.TEXT_INSERT)
            return
        val data = if (normalizeActionType == AbstractActionType.SWIPE) {
            val swipeData = Helper.parseSwipeData(interaction.data)
            Helper.getSwipeDirection(swipeData[0], swipeData[1])
        } else
            interaction.data
        val widget_action = Triple(widget?.uid, interaction.actionType, data)
        actionScore.putIfAbsent(widget_action, HashMap())
        actionScore[widget_action]!!.putIfAbsent(prevAbstractState.window, newWidgetScore)
        val currentScore = actionScore[widget_action]!![prevAbstractState.window]!!
        /*if (coverageIncreased ==0) {
            reward -= coverageIncreaseScore
        }*/
        if (prevState == currentState) {
            val newScore = currentScore - 0.9 * (currentScore)
            actionScore[widget_action]!![prevAbstractState.window] = newScore
        } else {
            val maxCurrentStateScore: Double
            val currentStateWidgetScores = actionScore.filter {
                (actionableWidgets.any { w -> w.uid == it.key.first } || it.key.first == null) && it.value.containsKey(
                    currentAbstractState.window
                )
            }
            if (currentStateWidgetScores.isNotEmpty())
                maxCurrentStateScore =
                    currentStateWidgetScores.map { it.value.get(currentAbstractState.window)!! }.maxOrNull()!!
            else
                maxCurrentStateScore = 0.0
            val newScore = currentScore + 0.5 * (reward + 0.9 * maxCurrentStateScore - currentScore)
            actionScore[widget_action]!![prevAbstractState.window] = newScore
        }
        if (lastExecutedTransition != null) {
            val executedInputs = prevAbstractState.getInputsByAbstractAction(lastExecutedTransition!!.abstractAction)
            executedInputs.filter { it.eventType != EventType.resetApp }.forEach {
                val currentScore1 = actionScore2.get(it)
                if (currentScore1 != null) {
                    if (prevState == currentState) {
                        actionScore2[it] = currentScore1 - 0.9 * (currentScore1)
                    } else {
                        val maxCurrentStateScore = actionScore2.filter {
                            currentAbstractState.getAvailableInputs().contains(it.key)
                        }.entries.maxByOrNull { it.value }
                        val newScore =
                            currentScore1 + 0.5 * (reward + 0.9 * (maxCurrentStateScore?.value ?: 0.0) - currentScore1)
                        actionScore2[it] = newScore
                    }
                }
            }
        }
    }

    private fun isScoreAction(interaction: Interaction<Widget>, prevState: State<*>): Boolean {
        if (interaction.targetWidget != null)
            return true
        val actionType = AbstractAction.normalizeActionType(interaction, prevState)
        return when (actionType) {
            AbstractActionType.PRESS_MENU, AbstractActionType.PRESS_BACK -> true
            else -> false
        }

    }


    private fun deriveNonLaunchAndResetNullTargetInteraction(
        prevState: State<*>,
        allAbstractTransitions: List<Edge<AbstractState, AbstractTransition>>,
        actionType: AbstractActionType,
        actionData: Any?,
        interactionData: Any?,
        currentAbstractState: AbstractState,
        interaction: Interaction<Widget>,
        prevAbstractState: AbstractState,
        prevWindowAbstractState: AbstractState?
    ) {
        val abstractTransition = AbstractTransition.findExistingAbstractTransitions(
            abstractTransitionSet = allAbstractTransitions.map { it.label },
            abstractAction = AbstractAction.getOrCreateAbstractAction(
                actionType = actionType,
                attributeValuationMap = null,
                extra = actionData,
                window = prevAbstractState.window
            ),
            source = prevAbstractState,
            dest = currentAbstractState
        )
        if (abstractTransition != null) {
            lastExecutedTransition = abstractTransition
            updateExistingAbstractTransition(
                abstractTransition, interaction, interactionData, prevWindowAbstractState
            )
        } else {
            createNewAbstractTransition(
                actionType,
                interaction,
                prevState,
                prevAbstractState,
                null,
                interactionData,
                currentAbstractState,
                prevWindowAbstractState
            )
        }

    }

    private fun deriveLaunchOrResetInteraction(
        prevGuiState: State<Widget>,
        allAbstractTransitions: List<Edge<AbstractState, AbstractTransition>>,
        actionType: AbstractActionType,
        actionData: Any?,
        currentAbstractState: AbstractState,
        interaction: Interaction<Widget>,
        prevAbstractState: AbstractState
    ) {
        if (actionType == AbstractActionType.LAUNCH_APP) {
            val abstractTransition = AbstractTransition.findExistingAbstractTransitions(
                abstractTransitionSet = allAbstractTransitions.map { it.label },
                abstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = actionType,
                    attributeValuationMap = null,
                    extra = null,
                    window = prevAbstractState.window
                ),
                source = prevAbstractState,
                dest = currentAbstractState
            )
            if (abstractTransition != null) {
                abstractTransition.isImplicit = false
                lastExecutedTransition = abstractTransition
                lastExecutedTransition!!.interactions.add(interaction)
                AbstractTransition.interaction_AbstractTransitionMapping.put(interaction,lastExecutedTransition!!)
            } else {
                val attributeValuationMap: AttributeValuationMap? = null
                createNewAbstractTransition(
                    actionType,
                    interaction,
                    prevGuiState,
                    prevAbstractState,
                    attributeValuationMap,
                    null,
                    currentAbstractState,
                    null
                )
            }
        }
        AbstractStateManager.INSTANCE.updateLaunchAndResetTransition()
    }

    private fun createNewAbstractTransition(
        actionType: AbstractActionType,
        interaction: Interaction<Widget>,
        prevGuiState: State<Widget>,
        sourceAbstractState: AbstractState,
        attributeValuationMap: AttributeValuationMap?,
        interactionData: Any?,
        destAbstractState: AbstractState,
        prevWindowAbstractState: AbstractState?
    ) {
        val lastExecutedAction = AbstractAction.getOrCreateAbstractAction(
            actionType,
            interaction,
            prevGuiState,
            sourceAbstractState,
            attributeValuationMap,
            this
        )
//        val prevWindow = windowStack.peek()
        val newAbstractInteraction = AbstractTransition(
            abstractAction = lastExecutedAction,
            interactions = HashSet(),
            isImplicit = false,
            /*prevWindow = prevWindow,*/
            data = interactionData,
            source = sourceAbstractState,
            dest = destAbstractState
        )
        newAbstractInteraction.interactions.add(interaction)
        AbstractTransition.interaction_AbstractTransitionMapping.put(interaction,newAbstractInteraction)
        /*if (prevWindowAbstractState != null)
            newAbstractInteraction.dependentAbstractStates.add(prevWindowAbstractState)*/
        /*if (newAbstractInteraction.dependentAbstractStates.map { it.window }.contains(destAbstractState.window)
            && newAbstractInteraction.guardEnabled == false
        ) {
            newAbstractInteraction.guardEnabled = true
        }*/
        newAbstractInteraction.computeGuaranteedAVMs()
        dstg.add(sourceAbstractState, destAbstractState, newAbstractInteraction)
//        newAbstractInteraction.updateGuardEnableStatus()
        lastExecutedTransition = newAbstractInteraction


    }

    var prevAbstractStateRefinement: Int = 0

    private fun normalizeActionType(actionName: String): AbstractActionType {
        val actionType = actionName
        var abstractActionType = when (actionType) {
            "Tick" -> AbstractActionType.CLICK
            "ClickEvent" -> AbstractActionType.CLICK
            "LongClickEvent" -> AbstractActionType.LONGCLICK
            else -> AbstractActionType.values().find { it.actionName.equals(actionType) }
        }
        if (abstractActionType == null) {
            throw Exception("No abstractActionType for $actionType")
        }
        return abstractActionType
    }

    private fun compareDataOrNot(abstractTransition: AbstractTransition, actionType: AbstractActionType): Boolean {
        if (actionType == AbstractActionType.SEND_INTENT || actionType == AbstractActionType.SWIPE) {
            return abstractTransition.data == actionType
        }
        return true
    }


    //endregion


    private val stateFailedDialogs = arrayListOf<Pair<State<*>, String>>()


    private val unreachableTargetComponentState = arrayListOf<State<*>>()
    fun addUnreachableTargetComponentState(state: State<*>) {
        org.atua.modelFeatures.ATUAMF.Companion.log.debug("Add unreachable target component activity: ${stateActivityMapping[state]}")
        if (unreachableTargetComponentState.find { it.equals(state) } == null)
            unreachableTargetComponentState.add(state)
    }


    private fun computeAbstractState(
        newState: State<*>,
        explorationContext: ExplorationContext<*, *, *>
    ): AbstractState {
        var currentActivity: String = ""
        runBlocking {
            currentActivity = getCurrentActivity()
        }
        if (activityAlias.containsKey(currentActivity))
            currentActivity = activityAlias[currentActivity]!!
        if (mainActivity == "") {
            mainActivity = explorationContext.apk.launchableMainActivityName
        }
        stateActivityMapping[newState] = currentActivity
        log.info("Computing Abstract State.")
        val newAbstractState = AbstractStateManager.INSTANCE.getOrCreateNewAbstractState(
            newState, currentActivity, currentRotation, null
        )
        if (newAbstractState.abstractTransitions.isEmpty()) {
            AbstractStateManager.INSTANCE.initAbstractInteractions(newAbstractState, newState)
        }

        if (ProbabilityBasedPathFinder.disableWindows1.contains(newAbstractState.window)) {
            ProbabilityBasedPathFinder.disableWindows1.remove(newAbstractState.window)
        }
        if (ProbabilityBasedPathFinder.disableWindows2.contains(newAbstractState.window)) {
            ProbabilityBasedPathFinder.disableWindows2.remove(newAbstractState.window)
        }
        newAbstractState.getAvailableActions(newState).forEach {
            if (ProbabilityBasedPathFinder.disableAbstractActions1.contains(it)) {
                ProbabilityBasedPathFinder.disableAbstractActions1.remove(it)
            }
            if (ProbabilityBasedPathFinder.disableAbstractActions2.contains(it)) {
                ProbabilityBasedPathFinder.disableAbstractActions2.remove(it)
            }
        }
        newAbstractState.getAvailableInputs().forEach {
            if (ProbabilityBasedPathFinder.disableInputs1.contains(it)) {
                ProbabilityBasedPathFinder.disableInputs1.remove(it)
            }
            if (ProbabilityBasedPathFinder.disableInputs2.contains(it)) {
                ProbabilityBasedPathFinder.disableInputs2.remove(it)
            }
        }
      /*  val windowId =
            newState.widgets.find { !it.isKeyboard }?.metaInfo?.find { it.contains("windowId") }?.split(" = ")?.get(1)
      */
        if (getAbstractState(newState) == null)
            throw Exception("State has not been derived")
//        AbstractStateManager.INSTANCE.updateLaunchAndResetAbstractTransitions(newAbstractState)
        increaseNodeVisit(abstractState = newAbstractState)
        log.info("Computing Abstract State. - DONE")
        val window = newAbstractState.window
        if (backupModifiedMethodsByWindow.containsKey(window)) {
            modifiedMethodsByWindow.put(window, backupModifiedMethodsByWindow[window]!!)
            backupModifiedMethodsByWindow.remove(window)
            val inputs = backupNotFullyExercisedTargetInputs
                .filter { it.widget == null && it.sourceWindow == window }
            notFullyExercisedTargetInputs.addAll(inputs)
            backupNotFullyExercisedTargetInputs.removeAll(inputs)
        }
        newAbstractState.EWTGWidgetMapping.values.distinct().forEach { w ->
            val inputs = backupNotFullyExercisedTargetInputs
                .filter { it.widget != null && it.widget == w }
            notFullyExercisedTargetInputs.addAll(inputs)
            backupNotFullyExercisedTargetInputs.removeAll(inputs)
        }
        return newAbstractState
    }

    private fun increaseNodeVisit(abstractState: AbstractState) {
        if (!windowVisitCount.containsKey(abstractState.window)) {
            windowVisitCount[abstractState.window] = 1

        } else {
            windowVisitCount[abstractState.window] = windowVisitCount[abstractState.window]!! + 1
        }
        if (!abstractStateVisitCount.contains(abstractState)) {
            abstractStateVisitCount[abstractState] = 1
        } else {
            abstractStateVisitCount[abstractState] = abstractStateVisitCount[abstractState]!! + 1
        }
        increaseVirtualAbstractStateVisitCount(abstractState)

    }

    private fun increaseVirtualAbstractStateVisitCount(abstractState: AbstractState) {
        val virtualAbstractState = AbstractStateManager.INSTANCE.ABSTRACT_STATES.find {
            it.window == abstractState.window
                    && it is VirtualAbstractState
        }
        if (virtualAbstractState != null) {
            if (!abstractStateVisitCount.contains(virtualAbstractState)) {
                abstractStateVisitCount[virtualAbstractState] = 1
                increaseVirtualAbstractStateVisitCount(virtualAbstractState)
            } else {
                abstractStateVisitCount[virtualAbstractState] = abstractStateVisitCount[virtualAbstractState]!! + 1
            }
        }
    }

    /**
     * Return: True if model is updated, otherwise False
     */
    private fun updateAppModel(
        prevState: State<*>,
        newState: State<*>,
        lastInteractions: List<Interaction<*>>,
        context: ExplorationContext<*, *, *>
    ): Boolean {
        //update lastChildExecutedEvent
        log.info("Updating App Model")
        if (statementMF != null) {
            measureTimeMillis {
                runBlocking {
                    while (!statementMF!!.statementRead) {
                        delay(1)
                    }
                }
            }.let {
                log.debug("Wait for reading coverage took $it millis")
            }
        }

        var updated: Boolean = false
        val statementCovered: Boolean
        if (statementMF!!.recentExecutedStatements.isEmpty())
            statementCovered = false
        else
            statementCovered = true
        measureTimeMillis {
            statementMF!!.statementRead = false
            val currentAbstractState = computeAbstractState(newState, context)
            AbstractStateManager.INSTANCE.unreachableAbstractState.remove(currentAbstractState)
            stateList.add(newState)


            val structureHashUUID = if (!stateStructureHashMap.containsKey(newState.uid)) {
                newState.widgets.distinctBy { it.uid }.fold(emptyUUID, { id, widget ->
                    // e.g. keyboard elements are ignored for uid computation within [addRelevantId]
                    // however different selectable auto-completion proposes are only 'rendered'
                    // such that we have to include the img id (part of configId) to ensure different state configuration id's if these are different
                    if (!widget.isKeyboard && !newState.isHomeScreen
                        && (widget.nlpText.isNotBlank() || widget.isInteractive || widget.isLeaf())) {
                        if (Helper.hasParentWithType(widget, newState, "WebView")) {
                            if (widget.resourceId.isNotBlank()) {
                                id + widget.id.uid
                            } else {
                                id
                            }
                        } else {
                            id + widget.id.uid
                        }
                    }
                    else
                        id
                }).also {
                    stateStructureHashMap.putIfAbsent(newState.uid, it)
                }
            } else
                stateStructureHashMap[newState.uid]!!

            stateVisitCount.putIfAbsent(structureHashUUID, 0)
            stateVisitCount[structureHashUUID] = stateVisitCount[structureHashUUID]!! + 1
            val guiStructureEncounterCnt = stateVisitCount[structureHashUUID]!!
            actionCount.initWidgetActionCounterForNewState(newState)
            val actionId = lastInteractions.last().actionId
            val screenshotFile = eContext!!.model.config.imgDst.resolve("$actionId.jpg")
            val windowFolder =
                eContext!!.model.config.baseDir.resolve("EWTG").resolve(currentAbstractState.window.toString())
            /*           if (!Files.exists(windowFolder))
                           Files.createDirectories(windowFolder)
                       if (Files.exists(screenshotFile)) {
                           saveGUIWidgetsImage(newState,currentAbstractState,screenshotFile,windowFolder)
                       }*/

            var prevAbstractState = getAbstractState(prevState)
            if (prevAbstractState == null && prevState != context.model.emptyState) {
                prevAbstractState = computeAbstractState(prevState, context)
                stateList.add(stateList.size - 1, prevState)
            }
            if (prevAbstractState != null) {
                if (!actionProcessedByATUAStrategy)
                    prevAbstractState.ignored = true
//                    prevAbstractState.window.ignored = true
            }
            necessaryCheckModel = true
            if (!newState.isHomeScreen && firstRun) {
                AbstractStateManager.INSTANCE.launchStates[AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH] = newState
                AbstractStateManager.INSTANCE.launchStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH] = newState
                firstRun = false
                necessaryCheckModel = false
            }
            if (newState.isAppHasStoppedDialogBox) {
                necessaryCheckModel = false
            }
            if (newState.isRequestRuntimePermissionDialogBox) {
                necessaryCheckModel = false
            }
            if (lastInteractions.isNotEmpty()) {
                lastExecutedTransition = null
                isActivityResult = false
                guiStateStack.push(prevState)
                updateAppStateStack(prevAbstractState, prevState, currentAbstractState, newState, fromLaunch)
                registerPrevWindowState(prevState, newState, lastInteractions.last())
                deriveAbstractInteraction(ArrayList(lastInteractions), prevState, newState, statementCovered)

                //update lastExecutedEvent
                if (lastExecutedTransition == null) {
                    org.atua.modelFeatures.ATUAMF.Companion.log.debug("lastExecutedEvent is null")
                    updated = false
                } else {
                    lastExecutedTransition!!.activated = true
                    if (reuseBaseModel) {
                        val prevWindowAbstractState: AbstractState? =
                            getPrevWindowAbstractState(traceId, transitionId - 1)
                        ModelBackwardAdapter.instance.checkingEquivalence(
                            newState,
                            currentAbstractState,
                            lastExecutedTransition!!,
                            prevWindowAbstractState,
                            this
                        )
                    }
                    if (prevAbstractState!!.belongToAUT() && currentAbstractState.isOutOfApplication && lastInteractions.size > 1) {
                        lastOpeningAnotherAppInteraction = lastInteractions.single()
                    }
                    if (!prevAbstractState.belongToAUT() && currentAbstractState.belongToAUT()) {
                        lastOpeningAnotherAppInteraction = null
                    }
                    updated = updateAppModelWithLastExecutedEvent(prevState, newState, lastInteractions)

                }

                // derive dialog type
                if (prevAbstractState != null && lastExecutedTransition != null) {
                    org.atua.modelFeatures.DialogBehaviorMonitor.Companion.instance.detectDialogType(
                        lastExecutedTransition!!,
                        prevState,
                        newState
                    )
                }
                if (lastExecutedTransition == null) {
                    org.atua.modelFeatures.ATUAMF.Companion.log.debug("Last executed Interaction is null")
                } else if (!doNotRefine && necessaryCheckModel
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.UNKNOWN
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.WAIT
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.ACTION_QUEUE
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.RESET_APP
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.LAUNCH_APP
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.SEND_INTENT
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.RANDOM_CLICK
                    && lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.RANDOM_KEYBOARD
                    && lastInteractions.size == 1
                ) {
                    val beforeAT = lastExecutedTransition

                    org.atua.modelFeatures.ATUAMF.Companion.log.info("Refining Abstract Interaction.")
                    prevAbstractStateRefinement = AbstractStateManager.INSTANCE.refineModel(
                        lastInteractions.single(),
                        prevState,
                        lastExecutedTransition!!
                    )
                    if (prevAbstractStateRefinement > 0) {
                        recomputeWindowStack()
                    }
                    lastExecutedTransition = dstg.edges().find { it.label.interactions.intersect(lastInteractions).isNotEmpty() }?.label
                    org.atua.modelFeatures.ATUAMF.Companion.log.info("Refining Abstract Interaction. - DONE")
                } else {
                    org.atua.modelFeatures.ATUAMF.Companion.log.debug("Return to a previous state. Do not need refine model.")
                }

            } else {
                updated = false
            }
            AbstractTransition.updateDisableTransitions()
        }.let {
            org.atua.modelFeatures.ATUAMF.Companion.log.debug("Update model took $it  millis")
        }
        actionProcessedByATUAStrategy = false

        return updated
    }

    private fun saveGUIWidgetsImage(
        guiState: State<*>,
        abstractState: AbstractState,
        screenshotFile: Path,
        targetDir: Path
    ) {
        val derivedWidgets = Helper.getVisibleWidgetsForAbstraction(guiState)
        val screenshot: BufferedImage = ImageIO.read(File(screenshotFile.toAbsolutePath().toString()))
        derivedWidgets.forEach { widget ->
            val avm = abstractState.getAttributeValuationSet(widget, guiState, this)
            if (avm != null) {
                val ewtgWidget = abstractState.EWTGWidgetMapping.get(avm)
                if (ewtgWidget != null) {
                    val widgetImg = screenshot.getSubimage(
                        widget.visibleBounds.leftX,
                        widget.visibleBounds.topY,
                        widget.visibleBounds.width,
                        widget.visibleBounds.height
                    )
                    val widgetImgFolder = targetDir.resolve(ewtgWidget.widgetId)
                    if (!Files.exists(widgetImgFolder)) {
                        Files.createDirectories(widgetImgFolder)
                    }
                    val widgetImgFilePath = widgetImgFolder.resolve(widget.id.toString() + ".jpg")
                    val widgetImgFile = File(widgetImgFilePath.toAbsolutePath().toString())
                    ImageIO.write(widgetImg, "jpg", widgetImgFile)
                }
            }
        }
    }

    var checkingDialog: Dialog? = null
    var isRandomExploration: Boolean = false
    val randomInteractions = HashSet<Int>()

    private fun updateAppModelWithLastExecutedEvent(
        prevState: State<*>,
        newState: State<*>,
        lastInteractions: List<Interaction<*>>
    ): Boolean {
        assert(statementMF != null, { "StatementCoverageMF is null" })
        val prevAbstractState = getAbstractState(prevState)
        if (prevAbstractState == null) {
            return false
        }
        val newAbstractState = getAbstractState(newState)!!
        if (lastExecutedTransition != null) {
            prevAbstractState.increaseActionCount2(lastExecutedTransition!!.abstractAction, this)
            AbstractStateManager.INSTANCE.addImplicitAbstractInteraction(
                newState, lastExecutedTransition!!, Pair(traceId, transitionId),prevState,newState
            )

            if (lastExecutedTransition!!.abstractAction.actionType == AbstractActionType.CLOSE_KEYBOARD) {
                if (prevAbstractState.attributeValuationMaps.any { !newAbstractState.attributeValuationMaps.contains(it) }) {
                    prevAbstractState.shouldNotCloseKeyboard = true
                }
            }
            if (lastExecutedTransition!!.abstractAction.isWidgetAction()) {
                val avm = lastExecutedTransition!!.abstractAction.attributeValuationMap!!
                if (avm.isUserLikeInput(lastExecutedTransition!!.source)) {
                    // If an AVM is an user-like input, after clicking on it, it should remain on the current app state
                    val widget = lastExecutedTransition!!.source.EWTGWidgetMapping.get(avm)!!
                    if (!widget.verifiedNotUserlikeInput && !lastExecutedTransition!!.dest.EWTGWidgetMapping.values.contains(widget)) {
                        // This is not a user-like input
                        widget.verifiedNotUserlikeInput = true
                    }
                }
            }
        }
        val abstractInteraction = lastExecutedTransition!!


        //Extract text input widget data
        val condition = HashMap(Helper.extractInputFieldAndCheckableWidget(prevState))
        val edge = dstg.edge(prevAbstractState, newAbstractState, abstractInteraction)
        if (edge == null)
            return false
        if (condition.isNotEmpty()) {
            if (!edge.label.userInputs.contains(condition)) {
                edge.label.userInputs.add(condition)
            }
        }

        updateCoverage(prevAbstractState, newAbstractState, abstractInteraction, lastInteractions.first())
        var coverageIncrease = statementMF!!.actionIncreasingCoverageTracking[lastInteractions.first().actionId.toString()]?.size
        if (coverageIncrease == null && lastInteractions.size==1)
            coverageIncrease = 0
        else if (coverageIncrease == null) {
            if ( lastInteractions.size > 1) {
                val containedInteraction = lastInteractions.find { statementMF!!.actionIncreasingCoverageTracking.containsKey(it.actionId.toString()) }
                if (containedInteraction!=null) {
                    coverageIncrease = statementMF!!.actionIncreasingCoverageTracking[containedInteraction.actionId.toString()]?.size
                }
            }
            if (coverageIncrease == null)
                coverageIncrease = 0
        }
        if (isRandomExploration == true) {
            val interaction = lastInteractions.first()
            randomInteractions.add(interaction.actionId)
            abstractInteraction.abstractAction.updateMeaningfulScore(interaction, newState, prevState,coverageIncrease>0,isRandomExploration, this)
            if (extraWebViewAbstractTransition!=null) {
                extraWebViewAbstractTransition!!.abstractAction.updateMeaningfulScore(interaction,newState,prevState,coverageIncrease>0,isRandomExploration,this)
            }
        }
        //create StaticEvent if it dose not exist in case this abstract Interaction triggered modified methods

        if (!prevAbstractState.isRequestRuntimePermissionDialogBox && !ignoredStates.contains(prevState)) {
            if (prevAbstractState.isOutOfApplication && newAbstractState.belongToAUT() && !abstractInteraction.abstractAction.isLaunchOrReset() && lastOpeningAnotherAppInteraction != null) {
                val lastAppState = stateList.find { it.stateId == lastOpeningAnotherAppInteraction!!.prevState }!!
                val lastAppAbstractState = getAbstractState(lastAppState)!!
                val lastOpenningAnotherAppAbstractInteraction =
                    findAbstractInteraction(lastOpeningAnotherAppInteraction)
                updateInputCoverage(
                    lastAppAbstractState,
                    lastOpenningAnotherAppAbstractInteraction!!,
                    lastInteractions.last(),
                    coverageIncrease,
                    prevState != newState
                )
            } else
                updateInputCoverage(
                    prevAbstractState,
                    abstractInteraction,
                    lastInteractions.last(),
                    coverageIncrease,
                    prevState != newState
                )
        }
        return true
    }




    private fun updateInputCoverage(
        prevAbstractState: AbstractState,
        abstractTransition: AbstractTransition,
        interaction: Interaction<Widget>,
        coverageIncreased: Int,
        stateChanged: Boolean
    ) {
        val inputs = prevAbstractState.getInputsByAbstractAction(abstractTransition.abstractAction)
        inputs.forEach {
            it.exerciseCount += 1
            if (it.eventType != EventType.resetApp) {
                if (it.eventType == EventType.implicit_rotate_event || it.eventType == EventType.press_menu || it.eventType == EventType.implicit_lifecycle_event) {
                    if (abstractTransition.modifiedMethods.isEmpty() || abstractTransition.modifiedMethods.all { it.value == false })
                        if (abstractTransition.handlers.isEmpty() || abstractTransition.handlers.all { it.value == false })
                            if (it.modifiedMethods.isNotEmpty() && it.modifiedMethods.all { it.value == false }) {
                                it.modifiedMethods.clear()
                                it.modifiedMethodStatement.clear()
                                notFullyExercisedTargetInputs.remove(it)
                            }
                }
                it.mappingActionIds.putIfAbsent(eContext!!.explorationTrace.id.toString(), ArrayList())
                it.mappingActionIds[eContext!!.explorationTrace.id.toString()]!!.add(interaction.actionId.toString())

                updateInputEffectiveness(it, interaction)
                updateInputEventHandlersAndModifiedMethods(it, abstractTransition, interaction, coverageIncreased)
                if (it.modifiedMethods.all { statementMF!!.fullyCoveredMethods.contains(it.key) }) {
                    if (notFullyExercisedTargetInputs.contains(it))
                        notFullyExercisedTargetInputs.remove(it)
                } else {
                    if (it.modifiedMethods.isNotEmpty() && !notFullyExercisedTargetInputs.contains(it)) {
                        notFullyExercisedTargetInputs.add(it)
                    }
                }
                modifiedMethodsByWindow.entries.removeIf {
                    it.value.all { statementMF!!.fullyCoveredMethods.contains(it) }
                }
                modifiedMethodsByWindow.entries.removeIf {
                    AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { appState -> appState.window == it.key  }.all { it.ignored }
                }
                if (abstractTransition.methodCoverage.isEmpty() && !stateChanged) {
                    if (it.isUseless == null)
                        it.isUseless = true
                } else {
                    it.isUseless = false
                }
            }
        }
        if (abstractTransition.modifiedMethods.isNotEmpty()) {
            var updateTargetWindow = prevAbstractState.window.isTargetWindowCandidate()
            /*if (prevAbstractState.window is Dialog) {
                val dialog = prevAbstractState.window as Dialog
                val activity = dialog.activityClass
                val activityNode = WindowManager.instance.updatedModelWindows.find { it is OutOfApp && it.activityClass == activity }
                if (activityNode != null) {
                    updateTargetWindow = true
                }
            }*/
            if (updateTargetWindow) {
                updateTargetWindow(prevAbstractState.window, abstractTransition, prevAbstractState)
                if (!prevAbstractState.window.isTargetWindowCandidate()) {
                    updateTargetWindow(abstractTransition.dest.window, abstractTransition, prevAbstractState)
                }
            }
        }

    }

    private fun updateInputReachability(
        input: Input,
        abstractTransition: AbstractTransition
    ) {
        if (AbstractStateManager.INSTANCE.goBackAbstractActions.contains(abstractTransition.abstractAction))
            return
        wtg.inputEnables.putIfAbsent(input, HashMap())
        val enableInputs = wtg.inputEnables.get(input)!!
        val sourceAbstractState = abstractTransition.source
        val srcAvailableInputs = sourceAbstractState.getAvailableInputs()
        val destAbstractState = abstractTransition.dest
        val destAvailableInputs = destAbstractState.getAvailableInputs()
        val diffInputs = ArrayList<Input>()
        if (abstractTransition.guardEnabled) {
            val beforeInputs =
                abstractTransition.dependentAbstractStates.map { it.getAvailableInputs() }.flatten().distinct()
            destAvailableInputs.subtract(beforeInputs).also {
                diffInputs.addAll(it)
            }
        } else {
            diffInputs.addAll(destAvailableInputs.subtract(srcAvailableInputs))
        }
        diffInputs.forEach {
            enableInputs.putIfAbsent(it, Pair(0, 0))
            val total = enableInputs[it]!!.first
            val enabled = enableInputs[it]!!.second
            enableInputs.put(it, Pair(total + 1, enabled + 1))
        }
        val unreachedInputs = enableInputs.keys.subtract(diffInputs)
        unreachedInputs.forEach {
            val total = enableInputs[it]!!.first
            val enabled = enableInputs[it]!!.second
            enableInputs.put(it, Pair(total + 1, enabled))
        }


    }

    private fun updateInputEffectiveness(
        input: Input,
        interaction: Interaction<Widget>
    ) {
        ModelHistoryInformation.INSTANCE.inputUsefulness.putIfAbsent(input, Pair(0, 0))
//        ModelHistoryInformation.INSTANCE.inputEffectiveness.put(input, Pair(0,0))

        val oldActionCnt = ModelHistoryInformation.INSTANCE.inputUsefulness[input]!!.first
        val oldIncreasingCnt = ModelHistoryInformation.INSTANCE.inputUsefulness[input]!!.second

        val newActionCnt = oldActionCnt + 1
        var newIncreasingCnt = oldIncreasingCnt
        if (oldActionCnt == 0 // this is the first time this input is exercised
        ) {
            if (statementMF!!.actionCoverageTracking[interaction.actionId.toString()]!!.size > 0
            ) {
                newIncreasingCnt += 1
            }
        } else {
            if (statementMF!!.actionIncreasingCoverageTracking[interaction.actionId.toString()]!!.size > 0
            ) {
                newIncreasingCnt += 1
            }
        }
        ModelHistoryInformation.INSTANCE.inputUsefulness.put(input, Pair(newActionCnt, newIncreasingCnt))
    }

    private fun updateTargetWindow(
        toUpdateWindow: Window,
        abstractTransition: AbstractTransition,
        prevAbstractState: AbstractState
    ) {
        if (!modifiedMethodsByWindow.contains(toUpdateWindow)) {
            modifiedMethodsByWindow[toUpdateWindow] = hashSetOf()
        }
        val windowModifiedMethods = modifiedMethodsByWindow.get(toUpdateWindow)!!
        abstractTransition.modifiedMethods.forEach { m, _ ->
            windowModifiedMethods.add(m)
        }
       /* val virtualAbstractState =
            AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it is VirtualAbstractState && it.window == toUpdateWindow }
                .firstOrNull()
        if (virtualAbstractState != null && virtualAbstractState.targetActions.contains(abstractTransition.abstractAction)) {
            virtualAbstractState.targetActions.add(abstractTransition.abstractAction)
        }*/
    }

    val guiInteractionCoverage = HashMap<Interaction<*>, HashSet<String>>()


    private fun updateCoverage(
        sourceAbsState: AbstractState,
        currentAbsState: AbstractState,
        abstractTransition: AbstractTransition,
        interaction: Interaction<Widget>
    ) {
        val edge = dstg.edge(sourceAbsState, currentAbsState, abstractTransition)
        if (edge == null)
            return
        val edgeStatementCoverage = edge.label.statementCoverage
        val edgeMethodCoverage = edge.label.methodCoverage
        if (!guiInteractionCoverage.containsKey(interaction)) {
            guiInteractionCoverage.put(interaction, HashSet())
        }
        val interactionCoverage = guiInteractionCoverage.get(interaction)!!

        val lastOpeningAnotherAppAbstractInteraction = findAbstractInteraction(lastOpeningAnotherAppInteraction)
        val recentExecutedStatements = ArrayList<String>()
        val recentExecutedMethods = ArrayList<String>()
        runBlocking {
            statementMF!!.mutex.withLock {
                recentExecutedStatements.addAll(statementMF!!.recentExecutedStatements)
                recentExecutedMethods.addAll(statementMF!!.recentExecutedMethods)
            }
        }



        recentExecutedStatements.forEach { statementId ->
            if (!interactionCoverage.contains(statementId)) {
                interactionCoverage.add(statementId)
            }
            if (!edgeStatementCoverage.contains(statementId)) {
                edgeStatementCoverage.add(statementId)
            }
            if (sourceAbsState.isOutOfApplication && currentAbsState.belongToAUT() && lastOpeningAnotherAppAbstractInteraction != null) {
                lastOpeningAnotherAppAbstractInteraction.updateUpdateStatementCoverage(statementId, this@ATUAMF)
            } else {
                abstractTransition.updateUpdateStatementCoverage(statementId, this@ATUAMF)
            }

        }

        recentExecutedMethods.forEach { methodId ->
            val methodName = statementMF!!.getMethodName(methodId)
            if (!edgeMethodCoverage.contains(methodId)) {
                edgeMethodCoverage.add(methodId)
            }
            if (unreachableModifiedMethods.contains(methodName)) {
                unreachableModifiedMethods.remove(methodName)
            }
            val isATopCallerOfModifiedMethods =
                modifiedMethodWithTopCallers.filter { it.value.contains(methodId) }.isNotEmpty()
            if (allEventHandlers.contains(methodId) || isATopCallerOfModifiedMethods) {
                if (sourceAbsState.isOutOfApplication && currentAbsState.belongToAUT() && lastOpeningAnotherAppAbstractInteraction != null) {
                    if (lastOpeningAnotherAppAbstractInteraction.handlers.containsKey(methodId)) {
                        lastOpeningAnotherAppAbstractInteraction.handlers[methodId] = true
                    } else {
                        lastOpeningAnotherAppAbstractInteraction.handlers.put(methodId, true)
                    }
                    if (isATopCallerOfModifiedMethods) {
                        val coverableModifiedMethods =
                            modifiedMethodWithTopCallers.filter { it.value.contains(methodId) }
                        coverableModifiedMethods.forEach { mmethod, _ ->
                            lastOpeningAnotherAppAbstractInteraction.modifiedMethods.putIfAbsent(mmethod, false)
                        }
                    }
                } else {
                    if (abstractTransition.handlers.containsKey(methodId)) {
                        abstractTransition.handlers[methodId] = true
                    } else {
                        abstractTransition.handlers.put(methodId, true)
                    }
                }
            }

            if (untriggeredTargetHiddenHandlers.contains(methodId)) {
                untriggeredTargetHiddenHandlers.remove(methodId)
            }
        }

        allTargetHandlers.removeIf { handler ->
            val invokableModifiedMethos = modifiedMethodWithTopCallers.filter { it.value.contains(handler) }.keys
            invokableModifiedMethos.all { statementMF!!.fullyCoveredMethods.contains(it) }
        }
    }

    private fun findAbstractInteraction(interaction: Interaction<Widget>?): AbstractTransition? {
        return if (interaction != null) {
            val lastAppState = stateList.find { it.stateId == interaction.prevState }
            if (lastAppState == null)
                throw Exception()
            val lastAppAbstractState = getAbstractState(lastAppState)!!
            lastAppAbstractState.abstractTransitions.find { it.interactions.contains(interaction) }
        } else {
            null
        }
    }

    fun updateInputEventHandlersAndModifiedMethods(
        input: Input,
        abstractTransition: AbstractTransition,
        interaction: Interaction<Widget>,
        coverageIncreased: Int
    ) {
        input.witnessed = true
        val prevWindows = abstractTransition.dependentAbstractStates.map { it.window }
        //update ewtg transitions


        // process event handlers
        abstractTransition.handlers.filter { it.value == true }.forEach {
            input.verifiedEventHandlers.add(it.key)
        }
        /* val similarInputs = notFullyExercisedTargetInputs.filter { it != input
                 && (it.eventHandlers.containsAll(input.eventHandlers) && input.eventHandlers.isNotEmpty())
                 }
         similarInputs.forEach {
             notFullyExercisedTargetInputs.remove(it)
         }*/
        if (input.verifiedEventHandlers.isEmpty()
            && abstractTransition.methodCoverage.isEmpty()
        ) {
            input.eventHandlers.clear()
            input.modifiedMethods.entries.removeIf { it.value == false }
            input.modifiedMethodStatement.entries.removeIf {
                val methodId = statementMF!!.statementMethodInstrumentationMap.get(it.key)
                !input.modifiedMethods.containsKey(methodId)
            }
        }
        if (input.verifiedEventHandlers.isNotEmpty() && input.eventHandlers.isEmpty()) {
            input.eventHandlers.addAll(input.verifiedEventHandlers)
        }
        // remove the event handlers detected by static analysis if they seems to be incorrectly detected
        // Case 1: there is no handler triggered at runtime
        if (input.verifiedEventHandlers.isEmpty() && input.eventHandlers.isNotEmpty()) {
            input.eventHandlers.clear()
            input.modifiedMethods.entries.removeIf { it.value == false }
            input.modifiedMethodStatement.entries.removeIf {
                val methodId = statementMF!!.statementMethodInstrumentationMap.get(it.key)
                !input.modifiedMethods.containsKey(methodId)
            }
        }
        // Case 2: the handler triggered at runtime is not the one detected by static analysis
        if (input.verifiedEventHandlers.isNotEmpty()
            && input.eventHandlers.isNotEmpty()
            && input.verifiedEventHandlers.intersect(input.eventHandlers).isEmpty()
        ) {
            input.eventHandlers.addAll(input.verifiedEventHandlers)
            input.modifiedMethods.entries.removeIf { it.value == false }
            input.modifiedMethodStatement.entries.removeIf {
                val methodId = statementMF!!.statementMethodInstrumentationMap.get(it.key)
                !input.modifiedMethods.containsKey(methodId)
            }
        }
        /*val inputCoveredMethods = input.coveredMethods.filter { it.value }.keys
        if (input.usefullOnce && inputCoveredMethods.isNotEmpty() && inputCoveredMethods.intersect(abstractTransition.methodCoverage).size != inputCoveredMethods.size) {
            input.usefullOnce = false
        }*/
        input.coveredMethods.putAll(abstractTransition.methodCoverage.associateWith { true })
        abstractTransition.modifiedMethods.forEach {
            val methodId = it.key
            if (input.modifiedMethods.containsKey(methodId)) {
                if (it.value == true) {
                    input.modifiedMethods[it.key] = it.value
                }
            } else {
                input.modifiedMethods[it.key] = it.value
                val methodId = it.key
                val modifiedStatements =
                    statementMF!!.statementMethodInstrumentationMap.filter { it2 -> it2.value == methodId }
                modifiedStatements.forEach { s, _ ->
                    input.modifiedMethodStatement.putIfAbsent(s, false)
                }
            }
        }
        val newCoveredStatements = ArrayList<String>()
        abstractTransition.modifiedMethodStatement.forEach {
            input.modifiedMethodStatement[it.key] = it.value
            newCoveredStatements.add(it.key)
        }
        if (input.modifiedMethods.isEmpty()) {
            if (notFullyExercisedTargetInputs.contains(input))
                notFullyExercisedTargetInputs.remove(input)
        } else {
            TargetInputReport.INSTANCE.praticalTargets.add(input)
            TargetInputClassification.INSTANCE.positiveTargetInputs.add(input)
            if (TargetInputClassification.INSTANCE.identifiedTargetInputsByStaticAnalysis.containsKey(input)) {
                val identifiedReachableMethods = TargetInputClassification.INSTANCE.identifiedTargetInputsByStaticAnalysis[input]!!
                input.modifiedMethods.forEach { m, b ->
                    if (identifiedReachableMethods.containsKey(m)) {
                        identifiedReachableMethods[m] = b
                    }
                }
            }

            if (!input.modifiedMethods.all { statementMF!!.fullyCoveredMethods.contains(it.key) }) {
                if (!notFullyExercisedTargetInputs.contains(input) && !input.sourceWindow.ignored) {
//                val handlerMethods = input.verifiedEventHandlers.map { statementMF!!.getMethodName(it) }
                    notFullyExercisedTargetInputs.add(input)
                }
                if (!modifiedMethodsByWindow.containsKey(input.sourceWindow)) {
                    modifiedMethodsByWindow.putIfAbsent(input.sourceWindow, HashSet())
                }
                modifiedMethodsByWindow[input.sourceWindow]!!.addAll(input.modifiedMethods.keys)
            }
        }
        if (coverageIncreased > 0) {
            log.debug("New $coverageIncreased updated statements covered by event: $input.")
            //log.debug(newCoveredStatements.toString())
        }
        // input.coverage[dateFormater.format(System.currentTimeMillis())] = input.modifiedMethodStatement.filterValues { it == true }.size
        removeUnreachableModifiedMethods(input)
    }

    private fun removeUnreachableModifiedMethods(input: Input) {
        val toRemovedModifiedMethods = ArrayList<String>()
        input.modifiedMethods.filter { !input.coveredMethods.containsKey(it.key) && it.value == false }.forEach {
            val methodName = it.key
            if (modifiedMethodWithTopCallers.containsKey(methodName)) {
                val handlers = modifiedMethodWithTopCallers[methodName]!!
                if (input.eventHandlers.intersect(handlers).isEmpty()) {
                    toRemovedModifiedMethods.add(methodName)
                }
            }
        }
        toRemovedModifiedMethods.forEach {
            input.modifiedMethods.remove(it)
        }
    }


    //endregion


    //region compute
    fun getProbabilities(state: State<*>): Map<Widget, Double> {
        try {
            runBlocking { mutex.lock() }
            val data = state.actionableWidgets
                .map { it to (widgetProbability[it.uid] ?: 0.0) }
                .toMap()

            assert(data.isNotEmpty()) { "No actionable widgets to be interacted with" }

            return data
        } finally {
            mutex.unlock()
        }
    }

    val unableFindTargetNodes = HashMap<Window, Int>()


    fun getCandidateActivity_P1(currentActivity: String): List<String> {
        val candidates = ArrayList<String>()
        //get possible target widgets
        targetInputsByWindowClass.filter { it.key != currentActivity }.forEach {
            if (it.value.size > 0)
                candidates.add(it.key)

        }
        return candidates
    }


    //endregion

    //region phase2

    fun validateEvent(e: Input, currentState: State<*>): List<AbstractAction> {
        if (e.eventType == EventType.implicit_rotate_event && !appRotationSupport) {
            if (notFullyExercisedTargetInputs.contains(e)) {
                notFullyExercisedTargetInputs.remove(e)
            }
            return emptyList()
        }
        val currentAbstractState = getAbstractState(currentState)!!
        val availableAbstractActions = currentAbstractState.getAbstractActionsWithSpecificInputs(e)
        val validatedAbstractActions = ArrayList<AbstractAction>()
        availableAbstractActions.forEach {
            if (!it.isWidgetAction()) {
                validatedAbstractActions.add(it)
            } else {
                if (it.attributeValuationMap!!.getGUIWidgets(currentState,currentAbstractState.window).isNotEmpty()) {
                    validatedAbstractActions.add(it)
                }
            }
        }
        return validatedAbstractActions
    }


    //endregion


    fun getRuntimeWidgets(
        attributeValuationMap: AttributeValuationMap,
        widgetAbstractState: AbstractState,
        currentState: State<*>
    ): List<Widget> {
        val allGUIWidgets = attributeValuationMap.getGUIWidgets(currentState,widgetAbstractState.window)
//        if (allGUIWidgets.isEmpty()) {
//            //try get the same static widget
//            val abstractState = getAbstractState(currentState)
//            if (abstractState == widgetAbstractState)
//                return allGUIWidgets
//            if (widgetAbstractState.EWTGWidgetMapping.containsKey(attributeValuationMap) && abstractState != null) {
//                val staticWidgets = widgetAbstractState.EWTGWidgetMapping[attributeValuationMap]!!
//                val similarWidgetGroups = abstractState.EWTGWidgetMapping.filter { it.value == staticWidgets }.map { it.key }
//                return similarWidgetGroups.map { it.getGUIWidgets(currentState) }.flatten()
//            }
//        }
        return allGUIWidgets
    }

    //endregion
    init {

    }

    //region statical analysis helper
    //endregion

    fun getAppName() = appName


    fun getAbstractState(state: State<*>): AbstractState? {
        return AbstractStateManager.INSTANCE.getAbstractState(state)
    }

    //region readJSONFile

    val methodTermsHashMap = HashMap<String, HashMap<String, Long>>()
    val windowTermsHashMap = HashMap<Window, HashMap<String, Long>>()
    val windowHandlersHashMap = HashMap<Window, Set<String>>()
    val activityAlias = HashMap<String, String>()
    val modifiedMethodWithTopCallers = HashMap<String, Set<String>>()


    fun getAppModelFile(): Path? {
        if (!Files.exists(resourceDir)) {
            org.atua.modelFeatures.ATUAMF.Companion.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val appModelFile = getAppModelFile(appName, resourceDir)
            if (appModelFile != null)
                return appModelFile
            else {
                org.atua.modelFeatures.ATUAMF.Companion.log.warn(
                    "Provided directory ($resourceDir) does not contain " +
                            "the corresponding instrumentation file."
                )
                return null
            }
        }
    }

    private fun getAppModelFile(apkName: String, targetDir: Path): Path? {
        return Files.list(targetDir)
            .filter {
                it.fileName.toString().contains(apkName)
                        && it.fileName.toString().endsWith("-AppModel.json")
            }
            .findFirst()
            .orElse(null)
    }

    private fun getEWTGDiffFile(apkName: String, targetDir: Path): Path? {
        return Files.list(targetDir)
            .filter {
                it.fileName.toString().contains(apkName)
                        && it.fileName.toString().endsWith("-ewtgdiff.json")
            }
            .findFirst()
            .orElse(null)
    }

    fun getTextInputFile(): Path? {
        if (!Files.exists(resourceDir)) {
            org.atua.modelFeatures.ATUAMF.Companion.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val textInputFile = getTextInputFile(appName, resourceDir)
            if (textInputFile != null)
                return textInputFile
            else {
                org.atua.modelFeatures.ATUAMF.Companion.log.warn(
                    "Provided directory ($resourceDir) does not contain " +
                            "the corresponding text input file."
                )
                return null
            }
        }
    }

    private fun getTextInputFile(apkName: String, targetDir: Path): Path? {
        return Files.list(targetDir)
            .filter {
                it.fileName.toString().contains(apkName)
                        && it.fileName.toString().endsWith("-input.json")
            }
            .findFirst()
            .orElse(null)
    }

    fun getDeviceConfigurationFile(): Path? {
        if (!Files.exists(resourceDir)) {
            org.atua.modelFeatures.ATUAMF.Companion.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val configurationFile = getDeviceConfigurationFile(appName, resourceDir)
            if (configurationFile != null)
                return configurationFile
            else {
                org.atua.modelFeatures.ATUAMF.Companion.log.warn(
                    "Provided directory ($resourceDir) does not contain " +
                            "the corresponding configuration file."
                )
                return null
            }
        }
    }

    private fun getDeviceConfigurationFile(apkName: String, targetDir: Path): Path? {
        return Files.list(targetDir)
            .filter {
                it.fileName.toString().contains(apkName)
                        && it.fileName.toString().endsWith("-configuration.json")
            }
            .findFirst()
            .orElse(null)
    }

    fun getIntentModelFile(): Path? {
        if (!Files.exists(resourceDir)) {
            org.atua.modelFeatures.ATUAMF.Companion.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val intentModelFile = getIntentModelFile(appName, resourceDir)
            if (intentModelFile != null)
                return intentModelFile
            else {
                org.atua.modelFeatures.ATUAMF.Companion.log.warn(
                    "Provided directory ($resourceDir) does not contain " +
                            "the corresponding intent model file."
                )
                return null
            }
        }
    }

    private fun getIntentModelFile(apkName: String, targetDir: Path): Path? {
        return Files.list(targetDir)
            .filter {
                it.fileName.toString().contains(apkName)
                        && it.fileName.toString().endsWith("-intent.json")
            }
            .findFirst()
            .orElse(null)
    }


    //endregion

    fun produceATUACoverageReport(context: ExplorationContext<*, *, *>) {
        log.info("Producing Coverage report...")
        val sb = StringBuilder()
        sb.appendln("Statements;${statementMF!!.statementInstrumentationMap.size}")
        sb.appendln("Methods;${statementMF!!.methodInstrumentationMap.size}")
        sb.appendln("ModifiedMethods;${statementMF!!.modMethodInstrumentationMap.size}")
        sb.appendln(
            "ModifiedMethodsStatements;${
                statementMF!!.statementMethodInstrumentationMap.filter {
                    statementMF!!.modMethodInstrumentationMap.contains(
                        it.value
                    )
                }.size
            } "
        )
        sb.appendln("CoveredStatements;${statementMF!!.executedStatementsMap.size}")
        sb.appendln("CoveredMethods;${statementMF!!.executedMethodsMap.size}")
        sb.appendln("CoveredModifiedMethods;${statementMF!!.executedModifiedMethodsMap.size}")
        sb.appendln("CoveredModifiedMethodsStatements;${statementMF!!.executedModifiedMethodStatementsMap.size}")
        sb.appendln("ListCoveredModifiedMethods;")
        val initialDate = statementMF!!.executedMethodsMap.entries.sortedBy { it.value }.first().value
        if (statementMF!!.executedModifiedMethodsMap.isNotEmpty()) {
            val sortedMethods = statementMF!!.executedModifiedMethodsMap.entries
                .sortedBy { it.value }
            sortedMethods
                .forEach {
                    sb.appendln(
                        "${it.key};${statementMF!!.modMethodInstrumentationMap[it.key]};${
                            Duration.between(
                                initialDate.toInstant(),
                                it.value.toInstant()
                            ).toMillis() / 1000
                        }"
                    )
                }

        }
        sb.appendln("EndOfList")
        sb.appendln("ListUnCoveredModifiedMethods;")
        statementMF!!.modMethodInstrumentationMap.filterNot { statementMF!!.executedModifiedMethodsMap.containsKey(it.key) }
            .forEach {
                sb.appendln("${it.key};${it.value}")
            }
        sb.appendln("EndOfList")
        sb.appendln("Phase1StatementMethodCoverage;$phase1StatementCoverage")
        sb.appendln("Phase1MethodCoverage;$phase1MethodCoverage")
        sb.appendln("Phase1ModifiedStatementCoverage;$phase1ModifiedStatementCoverage")
        sb.appendln("Phase1ModifiedMethodCoverage;$phase1ModifiedMethodCoverage")
        sb.appendln("Phase1ActionCount;$phase1Actions")
        sb.appendln("Phase2StatementMethodCoverage;$phase2StatementCoverage")
        sb.appendln("Phase2MethodCoverage;$phase2MethodCoverage")
        sb.appendln("Phase2ModifiedMethodCoverage;$phase2ModifiedCoverage")
        sb.appendln("Phase2ModifiedStatementCoverage;$phase2ModifiedStatementCoverage")
        sb.appendln("Phase2ActionCount;$phase2Actions")
        sb.appendln("Phase2StartTime:$phase2StartTime")
        sb.appendln("Phase3StartTime:$phase3StartTime")
        sb.appendln("ReachSuccessfullyTime:${GoToAnotherWindowTask.succeededCount}")
        sb.appendln("ReachUnsuccessfullyTime:${GoToAnotherWindowTask.failedCount}")
        sb.appendln("Reached windows;")
        WindowManager.instance.updatedModelWindows.filterNot {
            it is Launcher
                    || it is OutOfApp || it is FakeWindow
        }
            .filter { window ->
                AbstractStateManager.INSTANCE.ABSTRACT_STATES
                    .find { it.window == window
                            && it !is VirtualAbstractState
                            && it !is PredictedAbstractState} != null }
            .forEach {
                sb.appendln(it.toString())
            }
        sb.appendln("Unreached windows;")
        WindowManager.instance.updatedModelWindows.filterNot {
            it is Launcher
                    || it is OutOfApp || it is FakeWindow
        }
            .filter { window ->
                AbstractStateManager.INSTANCE.ABSTRACT_STATES
                    .find { it.window == window
                            && it !is VirtualAbstractState } == null }
            .forEach {
                sb.appendln(it.toString())
            }

        /*  sb.appendln("Unmatched widget: ${allTargetStaticWidgets.filter { it.mappedRuntimeWidgets.isEmpty() }.size}")
          allTargetStaticWidgets.forEach {
              if (it.mappedRuntimeWidgets.isEmpty())
              {
                  sb.appendln("${it.resourceIdName}-${it.className}-${it.widgetId} in ${it.activity}")
              }
          }*/

        val numberOfAppStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter{it !is VirtualAbstractState && it !is PredictedAbstractState && it.guiStates.isNotEmpty()}.size
        sb.appendln("NumberOfAppStates;$numberOfAppStates")

        val outputFile = context.model.config.baseDir.resolve(targetWidgetFileName)
        log.info(
            "Prepare writing coverage report file: " +
                    "\n- File name: ${outputFile.fileName}" +
                    "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}"
        )

        Files.write(outputFile, sb.lines())
        log.info("Finished writing report in ${outputFile.fileName}")

        if (reuseBaseModel) {
            ModelBackwardAdapter.instance.produceReport(context)
        }
        produceTargetIdentificationReport(context)
        produceStateUIDFirstReachingReport(context)
        produceStateReachingFailureReport(context)
    }

    private fun produceStateUIDFirstReachingReport(context: ExplorationContext<*, *, *>) {
        val outputFile = context.model.config.baseDir.resolve("stateUIDFirstReachingReport.csv")
        log.info("Prepare writing stateUID first reaching report file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}"
        )
        val sb = StringBuilder()
        sb.appendLine("UUID,ActionId")
        firstReachingStateUUID.forEach { uid, actionId ->
            sb.appendLine("${uid},$actionId")
        }
        Files.write(outputFile,sb.lines())
        log.info("Finished writing report in ${outputFile.fileName}")
    }

    fun produceTargetIdentificationReport(context: ExplorationContext<*,*,*>) {
        val outputFile = context.model.config.baseDir.resolve("targetIdentificationReport.csv")
        log.info("Prepare writing target indentification report file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}"
        )
        TargetInputClassification.INSTANCE.writeReport(outputFile.toString())
        log.info("Finished writing report in ${outputFile.fileName}")
    }

    fun produceStateReachingFailureReport(context: ExplorationContext<*,*,*>) {
        val outputFile = context.model.config.baseDir.resolve("stateReachingFailureReport.txt")
        log.info("Prepare writing target indentification report file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}"
        )
        FailReachingLog.INSTANCE.writeReport(outputFile.toString())
        log.info("Finished writing report in ${outputFile.fileName}")
    }

    fun accumulateTargetEventsDependency(): HashMap<Input, HashMap<String, Long>> {
        val result = HashMap<Input, HashMap<String, Long>>()
        notFullyExercisedTargetInputs.forEach { event ->
            val eventDependency = HashMap<String, Long>()
            event.coveredMethods.filter { it.value }.keys.forEach {
                if (methodTermsHashMap.containsKey(it)) {
                    if (methodTermsHashMap[it]!!.isNotEmpty()) {
                        methodTermsHashMap[it]!!.forEach { term, count ->
                            if (!eventDependency.containsKey(term))
                                eventDependency.put(term, count)
                            else
                                eventDependency[term] = eventDependency[term]!! + count
                        }
                    }
                }

            }
            if (eventDependency.isNotEmpty())
                result.put(event, eventDependency)
        }
        return result
    }

    fun updateStage1Info(eContext: ExplorationContext<*, *, *>) {
        phase1ModifiedMethodCoverage = statementMF!!.getCurrentModifiedMethodCoverage()
        phase1StatementCoverage = statementMF!!.getCurrentCoverage()
        phase1MethodCoverage = statementMF!!.getCurrentMethodCoverage()
        phase1ModifiedStatementCoverage = statementMF!!.getCurrentModifiedMethodStatementCoverage()
        phase1Actions = eContext.explorationTrace.getActions().size
        setPhase2StartTime()
    }

    fun updateStage2Info(eContext: ExplorationContext<*, *, *>) {
        phase2ModifiedCoverage = statementMF!!.getCurrentModifiedMethodCoverage()
        phase2StatementCoverage = statementMF!!.getCurrentCoverage()
        phase2MethodCoverage = statementMF!!.getCurrentMethodCoverage()
        phase2ModifiedStatementCoverage = statementMF!!.getCurrentModifiedMethodStatementCoverage()
        phase2Actions = eContext.explorationTrace.getActions().size
        setPhase3StartTime()
    }

    fun havingInternetConfiguration(window: Window): Boolean {
        if (deviceEnvironmentConfiguration == null)
            return false
        if (!deviceEnvironmentConfiguration!!.configurations.containsKey("Internet"))
            return false
        if (deviceEnvironmentConfiguration!!.configurations["Internet"]!!.contains(window))
            return true
        return false
    }

    fun getCandidateAction(
        currentState: State<*>,
        delay: Long,
        useCoordinator: Boolean
    ): Map<Widget?, List<ExplorationAction>> {
        val result = HashMap<Widget?, List<ExplorationAction>>()
        val currentAbstractState = getAbstractState(currentState)!!
        val actionableWidget = Helper.getActionableWidgetsWithoutKeyboard(currentState)
        val currentStateActionScores = actionScore
            .filter {
                (it.key.first != null
                        && actionableWidget.any { w -> w.uid == it.key.first })
                        && it.value.containsKey(currentAbstractState.window)
            }
            .map { Pair(it.key, it.value.get(currentAbstractState.window)!!) }.toMap()
        if (currentStateActionScores.isEmpty()) {
            result.put(null, listOf(ExplorationAction.pressBack()))
            return result
        }
        val excludedActions = ArrayList<Triple<UUID?, String, String>>()
        while (result.isEmpty()) {
            val availableCurrentStateScores = currentStateActionScores.filter { !excludedActions.contains(it.key) }
            if (availableCurrentStateScores.isEmpty()) {
                break
            }
            val maxCurrentStateScore = if (Random.nextDouble() < 0.5) {
                availableCurrentStateScores.maxByOrNull { it.value }!!.key
            } else {
                val pb = ProbabilityDistribution<Triple<UUID?, String, String>>(availableCurrentStateScores)
                pb.getRandomVariable()
            }
            if (maxCurrentStateScore.first != null) {
                val candidateWidgets = actionableWidget.filter { it.uid == maxCurrentStateScore.first }
                if (candidateWidgets.isEmpty()) {
                    result.put(null, listOf(ExplorationAction.pressBack()))
                    return result
                }
                val widgetActions = candidateWidgets.map { w ->
                    val actionList = if (maxCurrentStateScore.second != "Swipe") {
                        Helper.getAvailableActionsForWidget(w, currentState, delay, useCoordinator)
                            .filter { it.name == maxCurrentStateScore.second }
                    } else {
                        when (maxCurrentStateScore.third) {
                            "SwipeLeft" -> listOf<ExplorationAction>(w.swipeLeft())
                            "SwipeRight" -> listOf<ExplorationAction>(w.swipeRight())
                            "SwipeUp" -> listOf<ExplorationAction>(w.swipeUp())
                            "SwipeDown" -> listOf<ExplorationAction>(w.swipeDown())
                            else -> ArrayList<ExplorationAction>(
                                w.availableActions(delay, useCoordinator).filter { it is Swipe })
                        }
                    }
                    Pair<Widget, List<ExplorationAction>>(w, actionList)
                }
                val candidateActions = widgetActions.filter { it.second.isNotEmpty() }
                if (candidateActions.isNotEmpty()) {
                    candidateActions.forEach {
                        result.put(it.first, it.second)
                    }
                    ExplorationTrace.widgetTargets.clear()
                } else
                    excludedActions.add(maxCurrentStateScore)
            } else {
                val action: ExplorationAction = when (maxCurrentStateScore.second) {
                    "PressBack" -> ExplorationAction.pressBack()
                    "PressMenu" -> GlobalAction(ActionType.PressMenu)
                    else -> ExplorationAction.pressBack()
                }
                result.put(null, listOf(action))
                return result
            }
        }
        if (result.isEmpty()) {
            result.put(null, listOf(ExplorationAction.pressBack()))
            return result
        }
        //check candidate action
        return result
    }

    fun computeAppStatesScore() {
        //Initiate reachable modified methods list
        val abstractStatesScores = HashMap<AbstractState, Double>()
        val abstractStateProbabilityByWindow = HashMap<Window, ArrayList<Pair<AbstractState, Double>>>()

        val modifiedMethodWeights = HashMap<String, Double>()
        val modifiedMethodMissingStatements = HashMap<String, HashSet<String>>()
        val appStateModifiedMethodMap = HashMap<AbstractState, HashSet<String>>()
        val modifiedMethodTriggerCount = HashMap<String, Int>()
        val windowScores = HashMap<Window, Double>()
        val windowsProbability = HashMap<Window, Double>()
        modifiedMethodMissingStatements.clear()
        modifiedMethodTriggerCount.clear()
        appStateModifiedMethodMap.clear()
        modifiedMethodWeights.clear()
        val allTargetInputs = ArrayList(notFullyExercisedTargetInputs)

        val triggeredStatements = statementMF!!.getAllExecutedStatements()
        statementMF!!.getAllModifiedMethodsId().forEach {
            val methodName = statementMF!!.getMethodName(it)
            if (!unreachableModifiedMethods.contains(methodName)) {
                modifiedMethodTriggerCount.put(it, 0)
                val statements = statementMF!!.getMethodStatements(it)
                val missingStatements = statements.filter { !triggeredStatements.contains(it) }
                modifiedMethodMissingStatements.put(it, HashSet(missingStatements))
            }
        }
        allTargetInputs.removeIf {
            it.modifiedMethods.map { it.key }.all {
                modifiedMethodMissingStatements.containsKey(it) && modifiedMethodMissingStatements[it]!!.size == 0
            }
        }
        //get all AppState
        val appStateList = ArrayList<AbstractState>()
        AbstractStateManager.INSTANCE.getPotentialAbstractStates().forEach { appStateList.add(it) }

        //get all AppState's edges and appState's modified method
        val edges = ArrayList<Edge<AbstractState, AbstractTransition>>()
        appStateList.forEach { appState ->
            edges.addAll(dstg.edges(appState).filter { it.label.isExplicit() || it.label.fromWTG })
            appStateModifiedMethodMap.put(appState, HashSet())
            appState.abstractTransitions.map { it.modifiedMethods }.forEach { hmap ->
                hmap.forEach { m, _ ->
                    if (!appStateModifiedMethodMap[appState]!!.contains(m)) {
                        appStateModifiedMethodMap[appState]!!.add(m)
                    }
                }
            }
        }

        //for each edge, count modified method appearing
        edges.forEach { edge ->
            val coveredMethods = edge.label.methodCoverage
            coveredMethods.forEach {
                if (statementMF!!.isModifiedMethod(it)) {
                    if (modifiedMethodTriggerCount.containsKey(it)) {
                        modifiedMethodTriggerCount[it] = modifiedMethodTriggerCount[it]!! + edge.label.interactions.size
                    }
                }
            }
        }
        //calculate modified method score
        val totalAbstractInteractionCount = edges.size
        modifiedMethodTriggerCount.forEach { m, c ->
            val score = 1 - c / totalAbstractInteractionCount.toDouble()
            modifiedMethodWeights.put(m, score)
        }

        //calculate appState score
        appStateList.forEach {
            var appStateScore = 0.0
            if (appStateModifiedMethodMap.containsKey(it)) {
                appStateModifiedMethodMap[it]!!.forEach {
                    if (!modifiedMethodWeights.containsKey(it))
                        modifiedMethodWeights.put(it, 1.0)
                    val methodWeight = modifiedMethodWeights[it]!!
                    if (modifiedMethodMissingStatements.containsKey(it)) {
                        val missingStatementNumber = modifiedMethodMissingStatements[it]!!.size
                        appStateScore += (methodWeight * missingStatementNumber)
                    }
                }
                //appStateScore += 1
                abstractStatesScores.put(it, appStateScore)
            }
        }

        //calculate appState probability
        appStateList.groupBy { it.window }.forEach { window, abstractStateList ->
            var totalScore = 0.0
            abstractStateList.forEach {
                totalScore += abstractStatesScores[it]!!
            }

            val appStatesProbab = ArrayList<Pair<AbstractState, Double>>()
            abstractStateProbabilityByWindow.put(window, appStatesProbab)
            abstractStateList.forEach {
                val pb = abstractStatesScores[it]!! / totalScore
                appStatesProbab.add(Pair(it, pb))
            }
        }

        //calculate staticNode score
        var staticNodeTotalScore = 0.0
        windowScores.clear()
        modifiedMethodsByWindow.filter { abstractStateProbabilityByWindow.containsKey(it.key) }.forEach { n, _ ->
            var weight: Double = 0.0
            val modifiedMethods = HashSet<String>()
/*            appStateModifiedMethodMap.filter { it.key.staticNode == n}.map { it.value }.forEach {
                it.forEach {
                    if (!modifiedMethods.contains(it))
                    {
                        modifiedMethods.add(it)
                    }
                }
            }*/
            allTargetInputs.filter { it.sourceWindow == n }.forEach {
                modifiedMethods.addAll(it.modifiedMethods.map { it.key })

            }

            if (windowHandlersHashMap.containsKey(n)) {
                windowHandlersHashMap[n]!!.forEach { handler ->
                    val methods = modifiedMethodWithTopCallers.filter { it.value.contains(handler) }.map { it.key }
                    modifiedMethods.addAll(methods)
                }
            }

            modifiedMethods.filter { modifiedMethodWeights.containsKey(it) }.forEach {
                val methodWeight = modifiedMethodWeights[it]!!
                val missingStatementsNumber = modifiedMethodMissingStatements[it]?.size ?: 0
                weight += (methodWeight * missingStatementsNumber)
            }
            if (weight > 0.0) {
                windowScores.put(n, weight)
                staticNodeTotalScore += weight
            }
        }
        windowsProbability.clear()
        //calculate staticNode probability
        windowScores.forEach { n, s ->
            val pb = s / staticNodeTotalScore
            windowsProbability.put(n, pb)
        }
    }

    fun getAbstractStateStack(): Stack<AbstractState> {
        return abstractStateStack
    }

    fun getKeyboardClosedAbstractState(keyboardopenState: State<*>, tracing: Pair<Int, Int>): AbstractState? {
        var result: AbstractState? = null
        val keyboardOpenAbstractState = getAbstractState(keyboardopenState)!!
        for (i in tracing.second - 1 downTo 1) {
            val interaction = tracingInteractionsMap.get(Pair(tracing.first, i))?.lastOrNull()
            if (interaction == null)
                throw Exception()
            val resState = stateList.find { it.stateId == interaction.resState }!!
            val abstractState = getAbstractState(resState)!!
            if (abstractState.window == keyboardOpenAbstractState.window
                && !abstractState.isOpeningKeyboard
            ) {
                result = abstractState
                break
            }
            if (abstractState.window != keyboardOpenAbstractState.window)
                break
        }
        return result
    }

    val ignoredStates: HashSet<State<Widget>> = HashSet()
    fun registerNotProcessState(currentState: State<Widget>) {
        ignoredStates.add(currentState)
    }

    fun getRecentAbstractTransition(): AbstractTransition? {
        return lastExecutedTransition
    }


    companion object {

        @JvmStatic
        val log: Logger by lazy { LoggerFactory.getLogger(org.atua.modelFeatures.ATUAMF::class.java) }

        object RegressionStrategy : PropertyGroup() {
            val baseModelDir by stringType
            val use by booleanType
            val budgetScale by doubleType
            val manualInput by booleanType
            val manualIntent by booleanType
            val reuseBaseModel by booleanType
            val reuseSameVersionModel by booleanType
            val randomAfterTesting by booleanType
            val randomTimeout by intType
            val randomStrategy by intType
            val identifyObsolescentState by booleanType
        }
    }
}


enum class Rotation {
    LANDSCAPE,
    PORTRAIT
}