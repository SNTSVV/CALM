package org.droidmate.exploration.modelFeatures.atua

import com.natpryce.konfig.PropertyGroup
import com.natpryce.konfig.booleanType
import com.natpryce.konfig.doubleType
import com.natpryce.konfig.getValue
import com.natpryce.konfig.stringType

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.rotate
import org.droidmate.exploration.modelFeatures.ModelFeature
import org.droidmate.exploration.modelFeatures.explorationWatchers.CrashListMF
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.droidmate.exploration.modelFeatures.graph.StateGraphMF
import org.droidmate.exploration.modelFeatures.atua.inputRepo.textInput.InputConfiguration
import org.droidmate.exploration.modelFeatures.atua.DSTG.*
import org.droidmate.exploration.modelFeatures.atua.helper.PathFindingHelper
import org.droidmate.exploration.modelFeatures.atua.inputRepo.deviceEnvironment.DeviceEnvironmentConfiguration
import org.droidmate.exploration.modelFeatures.atua.inputRepo.intent.IntentFilter
import org.droidmate.exploration.modelFeatures.atua.EWTG.EventType
import org.droidmate.exploration.modelFeatures.atua.EWTG.Input
import org.droidmate.exploration.modelFeatures.atua.EWTG.EWTGWidget
import org.droidmate.exploration.modelFeatures.atua.EWTG.*
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.Activity
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.Dialog
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.DialogType
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.FakeWindow
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.Launcher
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.OptionsMenu
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.OutOfApp
import org.droidmate.exploration.modelFeatures.atua.EWTG.window.Window
import org.droidmate.exploration.modelFeatures.atua.ewtgdiff.EWTGDiff
import org.droidmate.exploration.modelFeatures.atua.helper.ProbabilityDistribution
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class ATUAMF(private val appName: String,
             private val resourceDir: Path,
             private val manualInput: Boolean,
             private val manualIntent: Boolean,
             private val reuseBaseModel: Boolean,
             private val baseModelDir: Path,
             private val getCurrentActivity: suspend () -> String,
             private val getDeviceRotation: suspend () -> Int,
             private val getDeviceScreenSurface: suspend () -> Rectangle) : ModelFeature() {
    val packageName = appName
    var portraitScreenSurface = Rectangle.empty()
    var portraitVisibleScreenSurface = Rectangle.empty()
    var landscapeScreenSurface = Rectangle.empty()
    var landscapeVisibleScreenSurface = Rectangle.empty()
    val textFilledValues = ArrayList<String>()
    private val targetWidgetFileName = "autaut-report.txt"
    override val coroutineContext: CoroutineContext = CoroutineName("RegressionTestingModelFeature") + Job()
    var statementMF: StatementCoverageMF? = null
    var crashlist: CrashListMF? = null
    var wtg: EWTG = EWTG()
    lateinit var DSTG: DSTG
    var stateGraph: StateGraphMF? = null
    private val abandonnedWTGNodes = arrayListOf<Window>()

    val interestingInteraction = HashMap<State<*>, ArrayList<Interaction<*>>>()
    val blackListWidgets = HashMap<AbstractState, Widget>()


    var isRecentItemAction: Boolean = false
    var isRecentPressMenu: Boolean = false

    var currentRotation: Rotation = Rotation.PORTRAIT
    var phase: Int = 1
    private val widgetProbability = mutableMapOf<UUID, Double>() // probability of each widget invoking modified methods
    private val runtimeWidgetInfos = mutableMapOf<Pair<Window, UUID>, Triple<State<*>, EWTGWidget, HashMap<String, Any>>>()//Key: widget id

    private val allMeaningfulWidgets = hashSetOf<EWTGWidget>() //widgetId -> idWidget
    val allTargetStaticWidgets = hashSetOf<EWTGWidget>() //widgetId -> idWidget
    val allTargetInputs = hashSetOf<Input>()
    val allTargetWindow_ModifiedMethods = hashMapOf<Window, HashSet<String>>()
    val allTargetHandlers = hashSetOf<String>()
    val allEventHandlers = hashSetOf<String>()
    val allModifiedMethod = hashMapOf<String, Boolean>()
    val widgets_modMethodInvocation = mutableMapOf<String, Widget_MethodInvocations>()
    val allDialogOwners = hashMapOf<String, ArrayList<String>>() // window -> listof (Dialog)

    private val allActivityOptionMenuItems = mutableMapOf<String, ArrayList<EWTGWidget>>()  //idWidget
    private val allContextMenuItems = arrayListOf<EWTGWidget>()
    private val activityTransitionWidget = mutableMapOf<String, ArrayList<EWTGWidget>>() // window -> Listof<StaticWidget>
    private val activity_TargetComponent_Map = mutableMapOf<String, ArrayList<Input>>() // window -> Listof<StaticWidget>

    val targetItemEvents = HashMap<Input, HashMap<String, Int>>()
    var isAlreadyRegisteringEvent = false
    private val stateActivityMapping = mutableMapOf<State<*>, String>()

    private val child_parentTargetWidgetMapping = mutableMapOf<Pair<Window, UUID>, Pair<Window, UUID>>() // child_widget.uid -> parent_widget.uid
    private val dateFormater = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    var lastExecutedTransition: AbstractTransition? = null
    private var lastChildExecutedEvent: AbstractTransition? = null
    private var lastTargetAbState: AbstractState? = null
    private var necessaryCheckModel: Boolean = false
    public var isModelUpdated: Boolean = false
        private set

    val optionsMenuCheck = ArrayList<AbstractState>()
    val openNavigationCheck = ArrayList<AbstractState>()
    val triedBlankInputCheck = ArrayList<AbstractState>()
    var isFisrtVisitedNode: Boolean = false
    var isRecentlyFillText = false
    var appRotationSupport = true
    var internetStatus = true
    val abstractStateVisitCount = HashMap<AbstractState, Int>()
    val windowVisitCount = HashMap<Window, Int>()
    val stateVisitCount = HashMap<State<*>, Int>()
    var appPrevState: State<*>? = null
    var windowStack: Stack<Window> = Stack<Window>()
    var abstractStateStack: Stack<Pair<AbstractState,State<*>?>> = Stack<Pair<AbstractState,State<*>?>>()
    val stateList: ArrayList<State<*>> = ArrayList()

    val guiState_AbstractStateMap = HashMap<State<*>, AbstractState>()
    // records how often a specific widget was selected and from which activity-eContext (widget.uid -> Map<activity -> numActions>)
    private val wCnt = HashMap<UUID, MutableMap<String, Int>>()
    private val actionScore = HashMap<Pair<UUID?, AbstractActionType>, MutableMap<UUID, Double>>()

    private var traceId = 0
    private var transitionId = 0

    val interactionsTracing = HashMap<List<Interaction<*>>, Pair<Int, Int>>()

    fun getUnexploredWidget(guiState: State<Widget>): List<Widget> {
        val unexploredWidget = ArrayList<Widget>()
        val abstractState = getAbstractState(guiState)!!
        val activity = abstractState.activity
        Helper.getActionableWidgetsWithoutKeyboard(guiState).forEach {
            val widgetUid = it.uid
            if (wCnt.containsKey(widgetUid)) {
                if (wCnt.get(widgetUid)!!.containsKey(activity)) {
                    if (wCnt.get(widgetUid)!!.get(activity) == 0) {
                        unexploredWidget.add(it)
                    }
                }
            }
        }
        return unexploredWidget
    }

    fun widgetnNumExplored(s: State<*>, selection: Collection<Widget>): Map<Widget, Int> {
        val abstractState = getAbstractState(s)!!
        val activity = abstractState.activity
        return selection.map {
            it to (wCnt[it.uid]?.get(activity) ?: 0)
        }.toMap()
    }


    var prevWindowState: State<*>? = null

    var lastOpeningAnotherAppInteraction: Interaction<Widget>? = null

    var updateMethodCovFromLastChangeCount: Int = 0
    var updateStmtCovFromLastChangeCount: Int = 0
    var methodCovFromLastChangeCount: Int = 0
    var stmtCovFromLastChangeCount: Int = 0
    var lastUpdatedMethodCoverage: Double = 0.0
    var lastMethodCoverage: Double = 0.0
    var lastUpdatedStatementCoverage: Double = 0.0


    val unreachableModifiedMethods = ArrayList<String>()

    val intentFilters = HashMap<String, ArrayList<IntentFilter>>()
    val targetIntFilters = HashMap<IntentFilter, Int>()
    var inputConfiguration: InputConfiguration? = null
    var deviceEnvironmentConfiguration: DeviceEnvironmentConfiguration? = null

    val inputWindowCorrelation = HashMap<Input, HashMap<Window, Double>>()
    val untriggeredTargetHandlers = hashSetOf<String>()


    var phase1MethodCoverage: Double = 0.0
    var phase2MethodCoverage: Double = 0.0
    var phase1ModifiedMethodCoverage: Double = 0.0
    var phase2ModifiedCoverage: Double = 0.0
    var phase1StatementCoverage: Double = 0.0
    var phase2StatementCoverage: Double = 0.0
    var phase1ModifiedStatementCoverage: Double = 0.0
    var phase2ModifiedStatementCoverage: Double = 0.0
    var phase1Actions: Int = 0
    var phase2Actions: Int = 0
    var phase3Actions: Int = 0
    var phase2StartTime: String = ""
    var phase3StartTime: String = ""

    fun setPhase2StartTime() {
        phase2StartTime = dateFormater.format(System.currentTimeMillis())
    }

    fun setPhase3StartTime() {
        phase3StartTime = dateFormater.format(System.currentTimeMillis())
    }

    fun getMethodCoverage(): Double {
        return statementMF!!.getCurrentMethodCoverage()
    }

    fun getStatementCoverage(): Double {
        return statementMF!!.getCurrentCoverage()
    }

    fun getModifiedMethodCoverage(): Double {
        return statementMF!!.getCurrentModifiedMethodCoverage()
    }

    fun getModifiedMethodStatementCoverage(): Double {
        return statementMF!!.getCurrentModifiedMethodStatementCoverage()
    }

    fun getTargetIntentFilters_P1(): List<IntentFilter> {
        return targetIntFilters.filter { it.value < 1 }.map { it.key }
    }

    private var mainActivity = ""
    fun isMainActivity(currentState: State<*>): Boolean = (stateActivityMapping[currentState] == mainActivity)


    /**
     * Mutex for synchronization
     *
     *
     */
    val mutex = Mutex()
    private var trace: ExplorationTrace<*, *>? = null
    private var eContext: ExplorationContext<*, *, *>? = null
    var fromLaunch = true
    var firstRun = true

    //region Model feature override
    override suspend fun onAppExplorationFinished(context: ExplorationContext<*, *, *>) {
        this.join()
        produceTargetWidgetReport(context)
        AutAutModelOutput.dumpModel(context.model.config, this)
    }

    override fun onAppExplorationStarted(context: ExplorationContext<*, *, *>) {
        this.eContext = context
        this.trace = context.explorationTrace
        this.stateGraph = context.getOrCreateWatcher<StateGraphMF>()
        this.statementMF = context.getOrCreateWatcher<StatementCoverageMF>()
        this.crashlist = context.getOrCreateWatcher<CrashListMF>()

        StaticAnalysisJSONParser.readAppModel(getAppModelFile()!!, this, manualIntent, manualInput)
        removeDuplicatedWidgets()
        processOptionsMenusWindow()
        AbstractStateManager.instance.init(this, appName)
        AbstractStateManager.instance.initVirtualAbstractStates()
        if (reuseBaseModel) {
            loadBaseModel()
        }

        allModifiedMethod.entries.removeIf { !statementMF!!.modMethodInstrumentationMap.containsKey(it.key) }
        WindowManager.instance.updatedModelWindows.forEach {
            it.inputs.forEach {
                val toremove = it.modifiedMethods.filter { !statementMF!!.modMethodInstrumentationMap.containsKey(it.key) }.keys
                toremove.forEach { method ->
                    it.modifiedMethods.remove(method)
                }
            }
        }

        modifiedMethodTopCallersMap.entries.removeIf { !statementMF!!.modMethodInstrumentationMap.containsKey(it.key) }
        val targetHandlers = modifiedMethodTopCallersMap.values.flatten().distinct()
        untriggeredTargetHandlers.clear()
        untriggeredTargetHandlers.addAll(targetHandlers)
        allTargetWindow_ModifiedMethods.entries.removeIf { it.key is Launcher || it.key is OutOfApp }
        allTargetWindow_ModifiedMethods.entries.removeIf {
            it.key.inputs.all { it.modifiedMethods.isEmpty() }
                    && (windowHandlersHashMap.get (it.key) == null
                    || (
                    windowHandlersHashMap.get(it.key) != null
                            && windowHandlersHashMap.get(it.key)!!.all { !targetHandlers.contains(it) }
                    ))
        }
        WindowManager.instance.updatedModelWindows.filterNot{ it is OutOfApp}. filter { it.inputs.any { it.modifiedMethods.isNotEmpty() } }.forEach {
            allTargetWindow_ModifiedMethods.putIfAbsent(it, HashSet())
            val allUpdatedMethods = allTargetWindow_ModifiedMethods.get(it)!!
            it.inputs.forEach {
                if (it.modifiedMethods.isNotEmpty()) {
                    allUpdatedMethods.addAll(it.modifiedMethods.keys)
                    allTargetInputs.add(it)
                }
            }
        }
        allTargetInputs.removeIf {
            it.modifiedMethods.isEmpty()
        }
        AbstractStateManager.instance.initAbstractInteractionsForVirtualAbstractStates()
        DSTG.edges().forEach {
            if (it.label.source !is VirtualAbstractState && it.label.dest !is VirtualAbstractState) {
                AbstractStateManager.instance.addImplicitAbstractInteraction(
                        currentState = null,
                        abstractTransition = it.label)
            }
        }
        appPrevState = null

    }

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
                    val childrenSignatures = widget.children.map { Pair(it,it.generateSignature()) }
                    childrenSignatures.groupBy { it.second }.filter{it.value.size>1 }.forEach { _, pairs ->
                        val keep = pairs.first().first
                        val removes = pairs.filter { it.first!=keep }
                        removes.map{it.first}. forEach {removewidget->
                            val relatedInputs = window.inputs.filter { it.widget == removewidget  }
                            relatedInputs.forEach {
                                it.widget = keep
                            }
                            window.widgets.remove(removewidget)
                        }
                    }
                }
            }
            val rootSignatures = roots.map {Pair(it,it.generateSignature())  }
            rootSignatures.groupBy { it.second }.filter{it.value.size>1 }.forEach { _, pairs ->
                val keep = pairs.first().first
                val removes = pairs.filter { it.first!=keep }
                removes.map{it.first}. forEach {removewidget->
                    val relatedInputs = window.inputs.filter { it.widget == removewidget  }
                    relatedInputs.forEach {
                        it.widget = keep
                    }
                    window.widgets.remove(removewidget)
                }
            }
        }
    }

    private fun loadBaseModel() {
        AutAutModelLoader.loadModel(baseModelDir.resolve(appName), this)
        val ewtgDiff = EWTGDiff.instance
        val ewtgDiffFile = getEWTGDiffFile(appName, resourceDir)
        if (ewtgDiffFile!=null)
            ewtgDiff.loadFromFile(ewtgDiffFile, this)
        WindowManager.instance.baseModelWindows.filter {
            it.isRuntimeCreated
                    && it !is FakeWindow
                    && it !is Launcher
                    && it !is OutOfApp
        }.forEach {
            WindowManager.instance.updatedModelWindows.add(it)
            WindowManager.instance.baseModelWindows.remove(it)
            resolveWindowNameConflict(it)
        }
    }

    private fun processOptionsMenusWindow() {
        WindowManager.instance.updatedModelWindows.filter {
            it is OptionsMenu
        }.forEach { menus ->
            val activity = WindowManager.instance.updatedModelWindows.find { it is Activity && it.classType == menus.classType }
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

        allTargetWindow_ModifiedMethods.entries.removeIf { !WindowManager.instance.updatedModelWindows.contains(it.key) }
    }

    private fun resolveWindowNameConflict(window: Window): Boolean {
        if (WindowManager.instance.updatedModelWindows.any {
                    it != window
                            && it.windowId == window.windowId
                }) {
            return true
        } else
            return false
    }

    override suspend fun onContextUpdate(context: ExplorationContext<*, *, *>) {
        //this.join()
        mutex.lock()
        try {
            log.info("RegressionTestingMF: Start OnContextUpdate")
            val interactions = ArrayList<Interaction<Widget>>()
            val lastAction = context.getLastAction()
            if (lastAction.actionType.isQueueEnd()) {
                val lastQueueStart = context.explorationTrace.getActions().last { it.actionType.isQueueStart() }
                val lastQueueStartIndex = context.explorationTrace.getActions().lastIndexOf(lastQueueStart)
                val lastLaunchAction = context.explorationTrace.getActions().last { it.actionType.isLaunchApp() || it.actionType == "ResetApp" }
                val lastLauchActionIndex = context.explorationTrace.getActions().lastIndexOf(lastLaunchAction)
                if (lastLauchActionIndex > lastQueueStartIndex) {
                    interactions.add(lastLaunchAction)
                } else {
                    context.explorationTrace.getActions()
                            .takeLast(context.explorationTrace.getActions().lastIndex - lastQueueStartIndex + 1)
                            .filterNot { it.actionType.isQueueStart() || it.actionType.isQueueEnd() || it.actionType.isFetch() }.let {
                                interactions.addAll(it)
                            }
                }
            } else {
                interactions.add(context.getLastAction())
            }
            if (interactions.any { it.actionType.isLaunchApp() || it.actionType == "ResetApp" }) {
                fromLaunch = true
                windowStack.clear()
                windowStack.push(Launcher.getOrCreateNode())
            } else {
                fromLaunch = false
            }
            isModelUpdated = false
            val prevState = context.getState(context.getLastAction().prevState) ?: context.model.emptyState
            val newState = context.getCurrentState()
            if (prevState == context.model.emptyState) {
                if (windowStack.isEmpty()) {
                    windowStack.push(Launcher.getOrCreateNode())
                }
            } else {
                appPrevState = prevState
                if (prevState.isHomeScreen) {
                    if (retrieveScreenDimension(prevState)) {
                        AbstractStateManager.instance.ABSTRACT_STATES.removeIf {
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
            if (windowStack.isEmpty()) {
                windowStack.push(Launcher.getOrCreateNode())
            }
            if (newState != context.model.emptyState) {
                if (newState.isAppHasStoppedDialogBox) {
                    log.debug("Encountering Crash state.")
                }
                if (newState.isHomeScreen) {
                    if (retrieveScreenDimension(newState)) {
                        AbstractStateManager.instance.ABSTRACT_STATES.removeIf {
                            it !is VirtualAbstractState
                                    && !it.loadedFromModel
                        }
                    }
                }
                currentRotation = computeRotation()
                lastExecutedTransition = null
                updateAppModel(prevState, newState, interactions, context)
                //validateModel(newState)
            }

        } finally {
            mutex.unlock()
        }
    }

    private fun initWidgetActionCounterForNewState(newState: State<*>) {
        val newAbstractState: AbstractState = getAbstractState(newState)!!
        Helper.getActionableWidgetsWithoutKeyboard(newState).forEach {
            val widgetUid = it.uid
            if (!wCnt.containsKey(widgetUid)) {
                wCnt.put(widgetUid, HashMap())
            }
            if (!wCnt.get(widgetUid)!!.containsKey(newAbstractState.activity)) {
                wCnt.get(widgetUid)!!.put(newAbstractState.activity, 0)


            }
        }
    }

    private fun validateModel(currentState: State<*>) {
        val currentAbstractState = getAbstractState(currentState)!!
        val runtimeAbstractStates = AbstractStateManager.instance.ABSTRACT_STATES
                .filter {
                    it !is VirtualAbstractState
                            && it != currentAbstractState
                }
        var pathStatus = HashMap<AbstractState, Boolean>()

        runtimeAbstractStates.forEach { dest ->
            val paths = ArrayList<TransitionPath>()
            PathFindingHelper.findPathToTargetComponent(
                    autautMF = this,
                    currentState = currentState,
                    root = currentAbstractState,
                    allPaths = paths,
                    finalTarget = dest,
                    pathCountLimitation = 1,
                    shortest = true,
                    pathType = PathFindingHelper.PathType.TRACE
            )
            if (paths.size > 0)
                pathStatus.put(dest, true)
            else {
                pathStatus.put(dest, false)
                // AbstractStateManager.instance.ABSTRACT_STATES.remove(dest)
                /* PathFindingHelper.findPathToTargetComponent(
                         autautMF = this,
                         currentState = currentState,
                         root = currentAbstractState,
                         allPaths = paths,
                         finalTarget = dest,
                         pathCountLimitation = 1,
                         shortest = true,
                         pathType = PathFindingHelper.PathType.TRACE
                 )*/

            }
        }
        if (pathStatus.any { it.value == false }) {
            log.debug("Unreachable abstract states.")
            pathStatus.filter { it.value == false }.forEach { abstrateState, _ ->
                val inEdges = DSTG.edges().filter {
                    it.destination?.data == abstrateState
                            && it.source != it.destination
                            && it.source.data !is VirtualAbstractState
                }
                log.debug("${inEdges.size} go to $abstrateState")
            }
        }
    }

    private fun retrieveScreenDimension(state: State<*>): Boolean {
        //get fullscreen app resolution
        val rotation = computeRotation()
        if (rotation == Rotation.PORTRAIT && portraitScreenSurface == Rectangle.empty()) {
            val fullDimension = Helper.computeGuiTreeDimension(state)
            val fullVisbleDimension = Helper.computeGuiTreeVisibleDimension(state)
            portraitScreenSurface = fullDimension
            portraitVisibleScreenSurface = fullVisbleDimension
            landscapeScreenSurface = Rectangle.create(fullDimension.topY, fullDimension.leftX, fullDimension.bottomY, fullDimension.rightX)
            landscapeVisibleScreenSurface = Rectangle.create(fullVisbleDimension.topY, fullVisbleDimension.leftX, fullVisbleDimension.bottomY, fullVisbleDimension.rightX)
            log.debug("Screen resolution: $portraitScreenSurface")
            return true
        } else if (rotation == Rotation.LANDSCAPE && landscapeScreenSurface == Rectangle.empty()) {
            val fullDimension = Helper.computeGuiTreeDimension(state)
            val fullVisbleDimension = Helper.computeGuiTreeVisibleDimension(state)
            landscapeScreenSurface = fullDimension
            landscapeVisibleScreenSurface = fullVisbleDimension
            portraitScreenSurface = Rectangle.create(fullDimension.topY, fullDimension.leftX, fullDimension.bottomY, fullDimension.rightX)
            portraitVisibleScreenSurface = Rectangle.create(fullVisbleDimension.topY, fullVisbleDimension.leftX, fullVisbleDimension.bottomY, fullVisbleDimension.rightX)
            log.debug("Screen resolution: $portraitScreenSurface")
            return true
        }
        return false
    }

    private fun updateWindowStack(prevAbstractState: AbstractState?, prevState: State<*>, currentAbstractState: AbstractState, currentState: State<*>, isLaunch: Boolean) {
        if (isLaunch) {
            windowStack.clear()
            abstractStateStack.clear()
            windowStack.push(Launcher.getOrCreateNode())
            abstractStateStack.push(Pair(AbstractStateManager.instance.ABSTRACT_STATES.find { it.window is Launcher }!!,stateList.findLast { it.isHomeScreen }))
            if (prevAbstractState != null && !prevAbstractState.isHomeScreen) {
                val homeScreenState = stateList.findLast { it.isHomeScreen }
                if (homeScreenState != null) {
                    stateList.add(homeScreenState)
                }
            }
            return
        }
        if (currentAbstractState.window !is OutOfApp) {
            if (prevAbstractState != null) {
                if (windowStack.contains(currentAbstractState.window) && windowStack.size > 1) {
                    // Return to the prev window
                    // Pop the window
                    abstractStateStack.pop()
                    while (windowStack.pop() != currentAbstractState.window) {
                        abstractStateStack.pop()
                    }
                } else {
                    if (currentAbstractState.window is Launcher) {
                        windowStack.clear()
                        abstractStateStack.clear()
                        windowStack.push(Launcher.getOrCreateNode())
                        abstractStateStack.push(Pair(AbstractStateManager.instance.ABSTRACT_STATES.find { it.window is Launcher}!!,stateList.findLast { it.isHomeScreen }))
                    } else if (currentAbstractState.window != prevAbstractState.window) {
                        necessaryCheckModel = true
                        if (prevAbstractState.window is Activity) {
                            windowStack.push(prevAbstractState.window)
                            abstractStateStack.push(Pair(prevAbstractState,prevState))
                        }
                    } else if (currentAbstractState.isOpeningKeyboard) {
                        windowStack.push(currentAbstractState.window)
                            abstractStateStack.push(Pair(currentAbstractState,currentState))
                    }
                }
            }
        }
        if (windowStack.isEmpty()) {
            windowStack.push(Launcher.getOrCreateNode())
            abstractStateStack.push(Pair(AbstractStateManager.instance.ABSTRACT_STATES.find { it.window is Launcher}!!,stateList.findLast { it.isHomeScreen } ))
            return
        }
        necessaryCheckModel = true
    }

    private fun computeRotation(): Rotation {
        /*val roots = newState.widgets.filter { !it.hasParent || it.resourceId=="android.id/content"}
        if (roots.isEmpty())
            return Rotation.PORTRAIT
        val root = roots.sortedBy { it.boundaries.height+it.boundaries.width }.last()
        val height = root.boundaries.height
        val width = root.boundaries.width
        if (height > width) {
            return Rotation.PORTRAIT
        }
        else
            return Rotation.LANDSCAPE*/
        var rotation: Int = 0
        runBlocking {
            rotation = getDeviceRotation()
        }
        if (rotation == 0 || rotation == 2)
            return Rotation.PORTRAIT
        return Rotation.LANDSCAPE
    }

    val guiInteractionList = ArrayList<Interaction<Widget>>()

    private fun deriveAbstractInteraction(interactions: ArrayList<Interaction<Widget>>, prevState: State<*>, currentState: State<*>, statementCovered: Boolean) {
        log.info("Computing Abstract Interaction.")
        if (interactions.isEmpty())
            return
        val prevAbstractState = AbstractStateManager.instance.getAbstractState(prevState)
        val currentAbstractState = AbstractStateManager.instance.getAbstractState(currentState)!!
        if (prevAbstractState == null)
            return
        if (interactions.size == 1) {
            val interaction = interactions.first()
            deriveSingleInteraction(prevAbstractState, interaction, currentAbstractState, prevState, currentState)

        } else {
            val actionType = AbstractActionType.ACTION_QUEUE
            val data = interactions
            val abstractAction = AbstractAction(
                    actionType = actionType,
                    attributeValuationMap = null,
                    extra = interactions
            )
            val abstractTransition = AbstractTransition(
                    abstractAction = abstractAction,
                    interactions = HashSet(),
                    isImplicit = false,
                    prevWindow = windowStack.peek(),
                    data = data,
                    source = prevAbstractState,
                    dest = currentAbstractState)
            DSTG.add(prevAbstractState, currentAbstractState, abstractTransition)

            lastExecutedTransition = abstractTransition
        }

        if (lastExecutedTransition == null) {
            log.info("Not processed interaction: ${interactions.toString()}")
            return
        }
        if (lastExecutedTransition!!.abstractAction.actionType != AbstractActionType.RESET_APP) {
            if (statementCovered || currentState != prevState) {
                transitionId++
                interactionsTracing.put(interactions, Pair(traceId, transitionId))
                lastExecutedTransition!!.tracing.add(Pair(traceId, transitionId))
            }
        }

        log.info("Computing Abstract Interaction. - DONE")

    }

    private fun deriveSingleInteraction(prevAbstractState: AbstractState, interaction: Interaction<Widget>, currentAbstractState: AbstractState, prevState: State<*>, currentState: State<*>) {
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
        /* val guiEdge = stateGraph!!.edge(prevState, currentState, interaction)
         if (guiEdge == null) {
             log.debug("GTG does not contain any transition from $prevState to $currentState via $interaction")
             stateGraph!!.add(prevState, currentState, interaction)
         }*/
        /*if (interaction.actionType == "RotateUI") {
            val prevStateRotation = pre
            if (prevStateRotation == currentRotation) {
                appRotationSupport = false
            }
        }*/
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
        var actionType: AbstractActionType = AbstractAction.normalizeActionType(interaction, prevState)
        val actionData = AbstractAction.computeAbstractActionExtraData(actionType, interaction, prevState, prevAbstractState, this)

        when (actionType) {
            AbstractActionType.LAUNCH_APP -> {
                AbstractStateManager.instance.launchStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH] = currentState
            }
            AbstractActionType.RESET_APP -> {
                AbstractStateManager.instance.launchStates[AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH] = currentState
                if (AbstractStateManager.instance.launchStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH] == null) {
                    AbstractStateManager.instance.launchStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH] = currentState
                }
            }
        }
        updateActionScore(currentState, prevState, interaction)
        if (interaction.targetWidget == null) {
            val allAbstractTransitions = DSTG.edges(prevAbstractState)
            if (actionType == AbstractActionType.RESET_APP || actionType == AbstractActionType.LAUNCH_APP) {
                setNewTrace()
                processLaunchOrResetInteraction( prevState, allAbstractTransitions, actionType, actionData, currentAbstractState, interaction, prevAbstractState)
            } else {
                processNonLaunchAndResetNullTargetInteraction(prevState, allAbstractTransitions, actionType, actionData, currentAbstractState, interaction, prevAbstractState)
            }
        } else {
            updateWidgetActionCounter(prevAbstractState, interaction)
            var widgetGroup = prevAbstractState.getAttributeValuationSet(interaction.targetWidget!!, prevState, this)
            if (widgetGroup != null) {
                val explicitInteractions = prevAbstractState.abstractTransitions.filter { it.isImplicit == false }
                val existingTransition = explicitInteractions.find {
                    it.abstractAction.actionType == actionType
                            && it.abstractAction.attributeValuationMap == widgetGroup
                            && it.abstractAction.extra == actionData
                            && it.prevWindow == windowStack.peek()
                            && it.isImplicit == false
                            && it.dest == currentAbstractState
                }
                if (existingTransition != null) {
                    lastExecutedTransition = existingTransition
                    lastExecutedTransition!!.interactions.add(interaction)
                } else {
                    //No recored abstract interaction before
                    //Or the abstractInteraction is implicit
                    //Record new AbstractInteraction
                    createNewAbstractTransition( actionType, interaction, prevState, prevAbstractState, widgetGroup, actionData, currentAbstractState)
                }
            } else {
                if (actionType == AbstractActionType.RANDOM_KEYBOARD) {
                    val explicitInteractions = prevAbstractState.abstractTransitions.filter { it.isImplicit == false }
                    val existingTransition = explicitInteractions.find {
                        it.abstractAction.actionType == actionType
                                && it.abstractAction.extra == actionData
                                && it.prevWindow == windowStack.peek()
                                && it.isImplicit == false
                                && it.dest == currentAbstractState
                    }
                    if (existingTransition != null) {
                        lastExecutedTransition = existingTransition
                        lastExecutedTransition!!.interactions.add(interaction)
                    } else {
                        //No recored abstract interaction before
                        //Or the abstractInteraction is implicit
                        //Record new AbstractInteraction
                        createNewAbstractTransition(actionType, interaction, prevState, prevAbstractState, null, actionData, currentAbstractState)
                    }
                } else {
                    log.debug("Cannot find the target widget's AVM")
                }
            }
        }

    }

    private fun setNewTrace() {
        traceId++
        transitionId = 0
    }

    var newWidgetScore = 1000.00
    var newActivityScore = 10000.00
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
        val actionableWidgets = Helper.getActionableWidgetsWithoutKeyboard(currentState).filter { !Helper.isUserLikeInput(it) }
        if (eContext!!.explorationCanMoveOn()) {
            actionableWidgets.groupBy { it.uid }.forEach { uid, w ->
                val actions = Helper.getAvailableActionsForWidget(w.first(), currentState, 0, false)
                actions.forEach { action ->
                    val widget_action = Pair(uid, normalizeActionType(action.name))
                    actionScore.putIfAbsent(widget_action, HashMap())
                    /*if (unexercisedWidgetCnt.contains(w)) {
                    reward += 10
                }*/
                    if (actionScore[widget_action]!!.values.isNotEmpty()) {
                        val avgScore = actionScore[widget_action]!!.values.average()
                        actionScore[widget_action]!!.putIfAbsent(currentState.uid, avgScore)
                    } else {
                        val score = if (normalizeActionType(action.name) == AbstractActionType.LONGCLICK) {
                            0.0
                        } else {
                            newWidgetScore
                        }
                        actionScore[widget_action]!!.putIfAbsent(currentState.uid, score)
                        if (!currentAbstractState.isOutOfApplication) {

                            reward += score
                            newWidgetCount++
                        }
                    }
                }
            }
            val pressBackAction = Pair<UUID?, AbstractActionType>(null, AbstractActionType.PRESS_BACK)
            val pressMenuAction = Pair<UUID?, AbstractActionType>(null, AbstractActionType.PRESS_MENU)
            val rotateUIAction = Pair<UUID?, AbstractActionType>(null, AbstractActionType.ROTATE_UI)
            val maximizeMinimize = Pair<UUID?, AbstractActionType>(null, AbstractActionType.MINIMIZE_MAXIMIZE)
            actionScore.putIfAbsent(pressBackAction, HashMap())
            actionScore.putIfAbsent(pressMenuAction, HashMap())
            actionScore.putIfAbsent(rotateUIAction, HashMap())
            actionScore.putIfAbsent(maximizeMinimize, HashMap())
            actionScore[pressBackAction]!!.putIfAbsent(currentState.uid, newWidgetScore)
            actionScore[pressMenuAction]!!.putIfAbsent(currentState.uid, newWidgetScore)
            actionScore[rotateUIAction]!!.putIfAbsent(currentState.uid, newWidgetScore)
            actionScore[maximizeMinimize]!!.putIfAbsent(currentState.uid, newWidgetScore)
        }

        if (newWidgetCount == 0)
            reward -= 1000
        if (stateVisitCount[currentState]!! == 1 && !currentState.widgets.any { it.isKeyboard }) {
            // this is a new state
            reward += newWidgetScore
        }
        if (!isScoreAction(interaction, prevState)) {
            return
        }
        val widget = interaction.targetWidget

        val prevAbstractState = getAbstractState(prevState)
        if (prevAbstractState == null) {
            return
        }
        if (AbstractAction.normalizeActionType(interaction, prevState) == AbstractActionType.TEXT_INSERT)
            return
        val widget_action = Pair(widget?.uid, AbstractAction.normalizeActionType(interaction, prevState))
        actionScore.putIfAbsent(widget_action, HashMap())
        actionScore[widget_action]!!.putIfAbsent(prevState.uid, newWidgetScore)
        val currentScore = actionScore[widget_action]!![prevState.uid]!!
        /*if (coverageIncreased ==0) {
            reward -= coverageIncreaseScore
        }*/
        if (prevState == currentState) {
            val newScore = currentScore - 0.5 * (currentScore)
            actionScore[widget_action]!![prevState.uid] = newScore
        } else {
            val maxCurrentStateScore: Double
            val currentStateWidgetScores = actionScore.filter { (actionableWidgets.any { w -> w.uid == it.key.first } || it.key.first == null) && it.value.containsKey(currentState.uid) }
            if (currentStateWidgetScores.isNotEmpty())
                maxCurrentStateScore = currentStateWidgetScores.map { it.value.get(currentState.uid)!! }.max()!!
            else
                maxCurrentStateScore = 0.0
            val newScore = currentScore + 0.5 * (reward + 0.9 * maxCurrentStateScore - currentScore)
            actionScore[widget_action]!![prevState.uid] = newScore
        }
    }

    private fun isScoreAction(interaction: Interaction<Widget>, prevState: State<*>): Boolean {
        if (interaction.targetWidget != null)
            return true
        val actionType = AbstractAction.normalizeActionType(interaction, prevState)
        return when (actionType) {
            AbstractActionType.PRESS_MENU, AbstractActionType.MINIMIZE_MAXIMIZE, AbstractActionType.CLOSE_KEYBOARD, AbstractActionType.PRESS_BACK, AbstractActionType.ROTATE_UI -> true
            else -> false
        }

    }

    private fun updateWidgetActionCounter(prevAbstractState: AbstractState, interaction: Interaction<Widget>) {
        //update widget count
        val prevActivity = prevAbstractState.activity
        val widgetUid = interaction.targetWidget!!.uid
        if (!wCnt.containsKey(widgetUid)) {
            wCnt.put(widgetUid, HashMap())
        }
        if (!wCnt.get(widgetUid)!!.containsKey(prevActivity)) {
            wCnt.get(widgetUid)!!.put(prevActivity, 0)
        }
        val currentCnt = wCnt.get(widgetUid)!!.get(prevActivity)!!
        wCnt.get(widgetUid)!!.put(prevActivity, currentCnt + 1)


    }

    private fun processNonLaunchAndResetNullTargetInteraction(prevState: State<*>, allAbstractTransitions: List<Edge<AbstractState, AbstractTransition>>, actionType: AbstractActionType, actionData: Any?, currentAbstractState: AbstractState, interaction: Interaction<Widget>, prevAbstractState: AbstractState) {
        val abstractTransition = allAbstractTransitions.find {
            it.label.abstractAction.actionType == actionType
                    && it.label.abstractAction.attributeValuationMap == null
                    && it.label.abstractAction.extra == actionData
                    && it.label.prevWindow == windowStack.peek()
                    && it.label.isImplicit == false
                    && it.label.dest == currentAbstractState
        }
        if (abstractTransition != null) {
            lastExecutedTransition = abstractTransition.label
            lastExecutedTransition!!.interactions.add(interaction)

        } else {
            createNewAbstractTransition( actionType, interaction, prevState, prevAbstractState, null, actionData, currentAbstractState)
        }

    }

    private fun processLaunchOrResetInteraction(prevGuiState: State<Widget>, allAbstractTransitions: List<Edge<AbstractState, AbstractTransition>>, actionType: AbstractActionType, actionData: Any?, currentAbstractState: AbstractState, interaction: Interaction<Widget>, prevAbstractState: AbstractState) {
        val abstractTransition = allAbstractTransitions.find {
            it.label.abstractAction.actionType == actionType
                    && it.label.abstractAction.attributeValuationMap == null
                    && it.label.abstractAction.extra == actionData
                    && it.label.dest == currentAbstractState
        }
        if (abstractTransition != null) {
            lastExecutedTransition = abstractTransition.label
            lastExecutedTransition!!.interactions.add(interaction)
        } else {
            // Remove old LaunchApp / ResetApp transition
            if (actionType == AbstractActionType.LAUNCH_APP) {
                val toRemoveEdges = allAbstractTransitions.filter { it.label.abstractAction.actionType == AbstractActionType.LAUNCH_APP }
                toRemoveEdges.forEach {
                    DSTG.remove(it)
                    it.source.data.abstractTransitions.remove(it.label)
                }
            }
            if (actionType == AbstractActionType.RESET_APP) {
                val toRemoveEdges = allAbstractTransitions.filter {
                    it.label.abstractAction.actionType == AbstractActionType.LAUNCH_APP
                            || it.label.abstractAction.actionType == AbstractActionType.RESET_APP
                }
                toRemoveEdges.forEach {
                    DSTG.remove(it)
                    it.source.data.abstractTransitions.remove(it.label)
                }
            }
            val attributeValuationMap: AttributeValuationMap? = null
            createNewAbstractTransition( actionType, interaction, prevGuiState, prevAbstractState, attributeValuationMap, actionData, currentAbstractState)
        }
    }

    private fun createNewAbstractTransition(actionType: AbstractActionType, interaction: Interaction<Widget>, prevGuiState: State<Widget>, prevAbstractState: AbstractState, attributeValuationMap: AttributeValuationMap?, actionData: Any?, currentAbstractState: AbstractState) {
        val lastExecutedAction = getOrCreateAbstractAction(actionType, interaction, prevGuiState, prevAbstractState, attributeValuationMap)
        val prevWindow = windowStack.peek()
        val newAbstractInteraction = AbstractTransition(
                abstractAction = lastExecutedAction,
                interactions = HashSet(),
                isImplicit = false,
                prevWindow = prevWindow,
                data = actionData,
                source = prevAbstractState,
                dest = currentAbstractState)
        newAbstractInteraction.interactions.add(interaction)
        DSTG.add(prevAbstractState, currentAbstractState, newAbstractInteraction)
        lastExecutedTransition = newAbstractInteraction
    }

    var prevAbstractStateRefinement: Int = 0
    private fun getOrCreateAbstractAction(actionType: AbstractActionType, interaction: Interaction<Widget>, guiState: State<Widget>, abstractState: AbstractState, attributeValuationMap: AttributeValuationMap?): AbstractAction {
        val actionData = AbstractAction.computeAbstractActionExtraData(actionType, interaction, guiState, abstractState, this)
        val abstractAction: AbstractAction
        val availableAction = abstractState.getAvailableActions().find {
            it.actionType == actionType
                    && it.attributeValuationMap == attributeValuationMap
                    && it.extra == actionData
        }

        if (availableAction == null) {
            abstractAction = AbstractAction(
                    actionType = actionType,
                    attributeValuationMap = attributeValuationMap,
                    extra = actionData
            )
            abstractState.addAction(abstractAction)
        } else {
            abstractAction = availableAction
        }
        return abstractAction
    }



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
    fun addFailedDialog(state: State<*>, dialogName: String) {
        stateFailedDialogs.add(Pair(state, dialogName))
    }

    private val unreachableTargetComponentState = arrayListOf<State<*>>()
    fun addUnreachableTargetComponentState(state: State<*>) {
        log.debug("Add unreachable target component activity: ${stateActivityMapping[state]}")
        if (unreachableTargetComponentState.find { it.equals(state) } == null)
            unreachableTargetComponentState.add(state)
    }


    private fun computeAbstractState(newState: State<*>, explorationContext: ExplorationContext<*, *, *>): AbstractState {

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
        val newAbstractState = AbstractStateManager.instance.getOrCreateNewAbstractState(
                newState, currentActivity, currentRotation, null)
        AbstractStateManager.instance.updateLaunchAndResetAbstractTransitions(newAbstractState)
        assert(newAbstractState.guiStates.contains(newState))
        increaseNodeVisit(abstractState = newAbstractState)
        log.info("Computing Abstract State. - DONE")
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
        val virtualAbstractState = AbstractStateManager.instance.ABSTRACT_STATES.find {
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
    private fun updateAppModel(prevState: State<*>, newState: State<*>, lastInteractions: List<Interaction<*>>, context: ExplorationContext<*, *, *>): Boolean {
        //update lastChildExecutedEvent
        log.info("Updating App Model")
        measureTimeMillis {
            runBlocking {
                while (!statementMF!!.statementRead) {
                    delay(1)
                }
            }
        }.let {
            log.debug("Wait for reading coverage took $it millis")
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
            stateList.add(newState)
            stateVisitCount.putIfAbsent(newState, 0)
            stateVisitCount[newState] = stateVisitCount[newState]!! + 1
            initWidgetActionCounterForNewState(newState)
            var prevAbstractState = getAbstractState(prevState)
            if (prevAbstractState == null && prevState != context.model.emptyState) {
                prevAbstractState = computeAbstractState(prevState, context)
                stateList.add(stateList.size - 1, prevState)
            }
            if (!newState.isHomeScreen && firstRun) {
                AbstractStateManager.instance.launchStates[AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH] = newState
                AbstractStateManager.instance.launchStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH] = newState
                firstRun = false
            }
            if (lastInteractions.isNotEmpty()) {
                lastExecutedTransition = null
                deriveAbstractInteraction(ArrayList(lastInteractions), prevState, newState, statementCovered)
                updateWindowStack(prevAbstractState,prevState, currentAbstractState,newState, fromLaunch)
                //update lastExecutedEvent
                if (lastExecutedTransition == null) {
                    log.debug("lastExecutedEvent is null")
                    updated = false
                } else {
                    updated = updateAppModelWithLastExecutedEvent(prevState, newState, lastInteractions)
                    if (reuseBaseModel) {
                        ModelBackwardAdapter.instance.checkingEquivalence(currentAbstractState, lastExecutedTransition!!)
                    }
                    if (prevAbstractState!!.belongToAUT() && currentAbstractState.isOutOfApplication && lastInteractions.size > 1) {
                        lastOpeningAnotherAppInteraction = lastInteractions.single()
                    }
                    if (!prevAbstractState.belongToAUT() && currentAbstractState.belongToAUT()) {
                        lastOpeningAnotherAppInteraction = null
                    }
                }
                // derive dialog type
                if (prevAbstractState != null && lastExecutedTransition != null) {
                    DialogBehaviorMonitor.instance.detectDialogType(lastExecutedTransition!!, prevState, newState)
                }
                if (lastExecutedTransition == null) {
                    log.debug("Last executed Interaction is null")
                } else if (necessaryCheckModel && lastInteractions.size==1) {
                    log.info("Refining Abstract Interaction.")
                    prevAbstractStateRefinement = AbstractStateManager.instance.refineModel(lastInteractions.single(), prevState, lastExecutedTransition!!)
     /*               if (prevAbstractStateRefinement==-1){
                        var currentTransitionId = transitionId
                        while (prevAbstractStateRefinement==-1) {
                            currentTransitionId = currentTransitionId - 1
                            if (currentTransitionId==0)
                                break
                            val interaction = interactionsTracing.entries.find { it.value==Pair(traceId,currentTransitionId) }?.key?.firstOrNull()
                            if (interaction == null)
                                break
                            val sourceState = stateList.find { interaction.prevState==it.stateId }
                            if (sourceState == null)
                                break
                            val sourceAbsState = getAbstractState(sourceState)!!
                            val abstractTransition = sourceAbsState.abstractTransitions.find { it.interactions.contains(interaction) }
                            if (abstractTransition==null)
                                break
                            prevAbstractStateRefinement = AbstractStateManager.instance.refineModel(interaction, sourceState, abstractTransition)
                        }
                    }*/
                    log.info("Refining Abstract Interaction. - DONE")
                } else {
                    log.debug("Return to a previous state. Do not need refine model.")
                }
            } else {
                updated = false
            }

        }.let {
            log.debug("Update model took $it  millis")
        }
        return updated
    }

    var checkingDialog: Dialog? = null

    private fun updateAppModelWithLastExecutedEvent(prevState: State<*>, newState: State<*>, lastInteractions: List<Interaction<*>>): Boolean {
        assert(statementMF != null, { "StatementCoverageMF is null" })
        val prevAbstractState = getAbstractState(prevState)
        if (prevAbstractState == null) {
            return false
        }
        val newAbstractState = getAbstractState(newState)!!
        if (lastExecutedTransition != null) {
            /*if (!(lastExecutedTransition!!.source.belongToAUT() && lastExecutedTransition!!.dest.isRequestRuntimePermissionDialogBox)) {


            } else if (lastExecutedTransition!!.dest.isRequestRuntimePermissionDialogBox) {
                log.debug("Recently open request runtime permission dialog.")
            }*/
            prevAbstractState.increaseActionCount2(lastExecutedTransition!!.abstractAction,true)
            AbstractStateManager.instance.addImplicitAbstractInteraction(newState, lastExecutedTransition!!)
        }
        val abstractInteraction = lastExecutedTransition!!


        //Extract text input widget data
        val condition = HashMap(Helper.extractInputFieldAndCheckableWidget(prevState))
        val edge = DSTG.edge(prevAbstractState, newAbstractState, abstractInteraction)
        if (edge == null)
            return false
        if (condition.isNotEmpty()) {
            if (!edge.label.userInputs.contains(condition)) {
                edge.label.userInputs.add(condition)
            }
        }
        //if (!abstractInteraction.abstractAction.isActionQueue())
        updateCoverage(prevAbstractState, newAbstractState, abstractInteraction, lastInteractions.first())
        //create StaticEvent if it dose not exist in case this abstract Interaction triggered modified methods
        if (!prevAbstractState.inputMappings.containsKey(abstractInteraction.abstractAction) && !abstractInteraction.abstractAction.isActionQueue() && !abstractInteraction.abstractAction.isLaunchOrReset()) {
            createStaticEventFromAbstractInteraction(prevAbstractState, newAbstractState, abstractInteraction, lastInteractions.first())
        }
        if (!prevAbstractState.isRequestRuntimePermissionDialogBox) {
            val coverageIncreased = statementMF!!.executedModifiedMethodStatementsMap.size - statementMF!!.prevUpdateCoverage
            if (prevAbstractState.isOutOfApplication && newAbstractState.belongToAUT() && !abstractInteraction.abstractAction.isLaunchOrReset() && lastOpeningAnotherAppInteraction != null) {
                val lastAppState = stateList.find { it.stateId == lastOpeningAnotherAppInteraction!!.prevState }!!
                val lastAppAbstractState = getAbstractState(lastAppState)!!
                val lastOpenningAnotherAppAbstractInteraction = findAbstractInteraction(lastOpeningAnotherAppInteraction)
                updateWindowTransitionCoverage(lastAppAbstractState, lastOpenningAnotherAppAbstractInteraction!!, coverageIncreased)
            } else
                updateWindowTransitionCoverage(prevAbstractState, abstractInteraction, coverageIncreased)

        }

        /* if (allTargetWindow_ModifiedMethods.containsKey(prevAbstractState.window) &&
                 newAbstractState.window.activityClass == prevAbstractState.window.activityClass
                 ) {
             if (!allTargetWindow_ModifiedMethods.containsKey(newAbstractState.window)) {
                 allTargetWindow_ModifiedMethods.put(newAbstractState.window, allTargetWindow_ModifiedMethods[prevAbstractState.window]!!)
                 if (windowHandlersHashMap.containsKey(prevAbstractState.window)) {
                     windowHandlersHashMap.put(newAbstractState.window, windowHandlersHashMap[prevAbstractState.window]!!)
                 }
             }
         }*/


        return true
    }

    private fun updateWindowTransitionCoverage(prevAbstractState: AbstractState, abstractTransition: AbstractTransition, coverageIncreased: Int) {
        val event = prevAbstractState.inputMappings[abstractTransition.abstractAction]
        if (event != null) {
            event.forEach {
                if (it.eventType != EventType.resetApp) {
                    updateStaticEventHandlersAndModifiedMethods(it, abstractTransition, coverageIncreased)
                    if (abstractTransition.modifiedMethods.isNotEmpty()) {
                        if (!allTargetInputs.contains(it))
                            allTargetInputs.add(it)
                    }

                }
            }
        }

        if (abstractTransition.modifiedMethods.isNotEmpty()
                && prevAbstractState.window.isTargetWindowCandidate()) {
            var updateTargetWindow = true
            /*if (prevAbstractState.window is Dialog) {
                val dialog = prevAbstractState.window as Dialog
                val activity = dialog.activityClass
                val activityNode = WindowManager.instance.updatedModelWindows.find { it is OutOfApp && it.activityClass == activity }
                if (activityNode != null) {
                    updateTargetWindow = true
                }
            }*/
            if (updateTargetWindow) {
                if (!allTargetWindow_ModifiedMethods.contains(prevAbstractState.window)) {
                    allTargetWindow_ModifiedMethods.put(prevAbstractState.window, hashSetOf())
                }
                val windowModifiedMethods = allTargetWindow_ModifiedMethods.get(prevAbstractState.window)!!
                abstractTransition.modifiedMethods.forEach { m, _ ->
                    windowModifiedMethods.add(m)
                }
                if (!prevAbstractState.targetActions.contains(abstractTransition.abstractAction)) {
                    prevAbstractState.targetActions.add(abstractTransition.abstractAction)
                }
                val virtualAbstractState = AbstractStateManager.instance.ABSTRACT_STATES.filter { it is VirtualAbstractState && it.window == prevAbstractState.window }.firstOrNull()
                if (virtualAbstractState != null && virtualAbstractState.targetActions.contains(abstractTransition.abstractAction)) {
                    virtualAbstractState.targetActions.add(abstractTransition.abstractAction)
                }
            }
        }

    }


    private fun createStaticEventFromAbstractInteraction(prevAbstractState: AbstractState, newAbstractState: AbstractState, abstractTransition: AbstractTransition, interaction: Interaction<Widget>?) {
        val eventType = Input.getEventTypeFromActionName(abstractTransition.abstractAction.actionType)
        if (eventType == EventType.fake_action || eventType == EventType.resetApp || eventType == EventType.implicit_launch_event)
            return
        if (interaction != null && interaction.targetWidget != null && interaction.targetWidget!!.isKeyboard)
            return
        var newInput: Input?
        if (abstractTransition.abstractAction.attributeValuationMap == null) {
            newInput = Input(
                    eventType = eventType,
                    widget = null,
                    sourceWindow = prevAbstractState.window,
                    eventHandlers = HashSet(),
                    createdAtRuntime = true
            )
            newInput.data = abstractTransition.abstractAction.extra
            newInput.eventHandlers.addAll(abstractTransition.handlers.map { it.key })
            if (newInput.eventHandlers.intersect(allTargetHandlers).isNotEmpty()) {
                allTargetInputs.add(newInput)
            }
            wtg.add(prevAbstractState.window, newAbstractState.window, WindowTransition(
                    prevAbstractState.window,
                    newAbstractState.window,
                    newInput,
                    abstractTransition.prevWindow
            ))
            if (!prevAbstractState.inputMappings.containsKey(abstractTransition.abstractAction)) {
                prevAbstractState.inputMappings.put(abstractTransition.abstractAction, arrayListOf())
            }
            prevAbstractState.inputMappings.get(abstractTransition.abstractAction)!!.add(newInput)
            AbstractStateManager.instance.ABSTRACT_STATES.filterNot { it == prevAbstractState }.filter { it.window == prevAbstractState.window }.forEach {
                val similarAbstractAction = it.getAvailableActions().find { it == abstractTransition.abstractAction }
                if (similarAbstractAction != null) {
                    it.inputMappings.put(similarAbstractAction, arrayListOf(newInput!!))
                }
            }
        } else {
            val attributeValuationSet = abstractTransition.abstractAction.attributeValuationMap
            if (!prevAbstractState.EWTGWidgetMapping.containsKey(attributeValuationSet)) {
                val attributeValuationSetId = if (attributeValuationSet.getResourceId().isBlank())
                    ""
                else
                    attributeValuationSet.avmId
                // create new static widget and add to the abstract state
                val staticWidget = EWTGWidget(
                        widgetId = attributeValuationSet.avmId.toString(),
                        resourceIdName = attributeValuationSet.getResourceId(),
                        window = prevAbstractState.window,
                        className = attributeValuationSet.getClassName(),
                        text = attributeValuationSet.getText(),
                        contentDesc = attributeValuationSet.getContentDesc(),
                        createdAtRuntime = true,
                        widgetUUID = attributeValuationSetId
                )
                prevAbstractState.EWTGWidgetMapping.put(attributeValuationSet, staticWidget)
                AbstractStateManager.instance.ABSTRACT_STATES.filterNot { it == prevAbstractState }.filter { it.window == prevAbstractState.window }.forEach {
                    val similarWidget = it.attributeValuationMaps.find { it == attributeValuationSet }
                    if (similarWidget != null) {
                        it.EWTGWidgetMapping.put(similarWidget, staticWidget)
                    }
                }
            }
            if (prevAbstractState.EWTGWidgetMapping.contains(attributeValuationSet)) {
                val staticWidget = prevAbstractState.EWTGWidgetMapping[attributeValuationSet]!!
                allTargetStaticWidgets.add(staticWidget)
                newInput = Input(
                        eventType = eventType,
                        widget = staticWidget,
                        sourceWindow = prevAbstractState.window,
                        eventHandlers = HashSet(),
                        createdAtRuntime = true
                )
                newInput.data = abstractTransition.abstractAction.extra
                newInput.eventHandlers.addAll(abstractTransition.handlers.map { it.key })

                wtg.add(prevAbstractState.window, newAbstractState.window, WindowTransition(
                        prevAbstractState.window,
                        newAbstractState.window,
                        newInput,
                        abstractTransition.prevWindow
                ))
                if (!prevAbstractState.inputMappings.containsKey(abstractTransition.abstractAction)) {
                    prevAbstractState.inputMappings.put(abstractTransition.abstractAction, arrayListOf())
                }
                prevAbstractState.inputMappings.get(abstractTransition.abstractAction)!!.add(newInput)
                AbstractStateManager.instance.ABSTRACT_STATES.filterNot { it == prevAbstractState }.filter { it.window == prevAbstractState.window }.forEach {
                    val similarAbstractAction = it.getAvailableActions().find { it == abstractTransition.abstractAction }
                    if (similarAbstractAction != null) {
                        it.inputMappings.put(similarAbstractAction, arrayListOf(newInput))
                    }
                }
            }
        }
/*        if (newInput!=null && abstractInteraction.modifiedMethods.any { it.value == true }) {
            log.debug("New target Window Transition detected: $newInput")
            allTargetStaticEvents.add(newInput!!)
        }*/

    }

    val guiInteractionCoverage = HashMap<Interaction<*>, HashSet<String>>()


    private fun updateCoverage(sourceAbsState: AbstractState, currentAbsState: AbstractState, abstractTransition: AbstractTransition, interaction: Interaction<Widget>) {
        val edge = DSTG.edge(sourceAbsState, currentAbsState, abstractTransition)
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
            val isATopCallerOfModifiedMethods = modifiedMethodTopCallersMap.filter { it.value.contains(methodId) }.isNotEmpty()
            if (allEventHandlers.contains(methodId) || isATopCallerOfModifiedMethods) {

                if (sourceAbsState.isOutOfApplication && currentAbsState.belongToAUT() && lastOpeningAnotherAppAbstractInteraction != null) {
                    if (lastOpeningAnotherAppAbstractInteraction.handlers.containsKey(methodId)) {
                        lastOpeningAnotherAppAbstractInteraction.handlers[methodId] = true
                    } else {
                        lastOpeningAnotherAppAbstractInteraction.handlers.put(methodId, true)
                    }
                    if (isATopCallerOfModifiedMethods) {
                        val coverableModifiedMethods = modifiedMethodTopCallersMap.filter { it.value.contains(methodId) }
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
                    if (isATopCallerOfModifiedMethods) {
                        val coverableModifiedMethods = modifiedMethodTopCallersMap.filter { it.value.contains(methodId) }
                        coverableModifiedMethods.forEach { mmethod, _ ->
                            abstractTransition.modifiedMethods.putIfAbsent(mmethod, false)
                        }

                    }
                }
            }

            if (untriggeredTargetHandlers.contains(methodId)) {
                untriggeredTargetHandlers.remove(methodId)
            }

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

    private fun updateStaticEventHandlersAndModifiedMethods(input: Input, abstractTransition: AbstractTransition, coverageIncreased: Int) {
        //update ewtg transition
        val existingTransition = wtg.edge(abstractTransition.source.window, abstractTransition.dest.window, WindowTransition(
                abstractTransition.source.window,
                abstractTransition.dest.window,
                input,
                abstractTransition.prevWindow
        ))
        if (existingTransition == null) {
            wtg.add(abstractTransition.source.window, abstractTransition.dest.window, WindowTransition(
                    abstractTransition.source.window,
                    abstractTransition.dest.window,
                    input,
                    abstractTransition.prevWindow
            ))
        }
        abstractTransition.modifiedMethods.forEach {
            if (it.value == false) {
                input.modifiedMethods.putIfAbsent(it.key, false)
            } else {
                input.modifiedMethods[it.key] = it.value
            }
        }
        val newCoveredStatements = ArrayList<String>()
        abstractTransition.modifiedMethodStatement.filter { it.value == true }.forEach {
            if (!input.modifiedMethodStatement.containsKey(it.key) || input.modifiedMethodStatement.get(it.key) == false) {
                input.modifiedMethodStatement[it.key] = it.value
                newCoveredStatements.add(it.key)

            }
        }
        if (coverageIncreased > 0) {
            log.debug("New $coverageIncreased updated statements covered by event: $input.")
            //log.debug(newCoveredStatements.toString())
        }
        input.coverage.put(dateFormater.format(System.currentTimeMillis()), input.modifiedMethodStatement.filterValues { it == true }.size)
        abstractTransition.handlers.filter { it.value == true }.forEach {
            input.verifiedEventHandlers.add(it.key)
            input.eventHandlers.add(it.key)
        }
        val eventhandlers = ArrayList(input.eventHandlers)
        eventhandlers.forEach {
            if (!input.verifiedEventHandlers.contains(it)) {
                if (!abstractTransition.handlers.containsKey(it)) {
                    input.eventHandlers.remove(it)
                } else if (abstractTransition.handlers[it] == false) {
                    input.eventHandlers.remove(it)
                } else {
                    if (!input.eventHandlers.contains(it)) {
                        input.eventHandlers.add(it)
                    }
                    input.verifiedEventHandlers.add(it)
                }
            }
        }
        if (input.eventHandlers.intersect(allTargetHandlers).isNotEmpty()) {
            allTargetInputs.add(input)
        }
        removeUnreachableModifiedMethods(input)

    }

    private fun removeUnreachableModifiedMethods(input: Input) {
        val toRemovedModifiedMethods = ArrayList<String>()
        input.modifiedMethods.forEach {
            val methodName = it.key
            if (modifiedMethodTopCallersMap.containsKey(methodName)) {
                val handlers = modifiedMethodTopCallersMap[methodName]!!
                if (input.eventHandlers.intersect(handlers).isEmpty()) {
                    toRemovedModifiedMethods.add(methodName)
                }
            }
        }
//        toRemovedModifiedMethods.forEach {
//            input.modifiedMethods.remove(it)
//        }
    }


    //endregion

/*    private fun getCurrentEventCoverage() {
        val triggeredEventCount = allTargetStaticEvents.size - untriggeredTargetEvents.size
        // log.debug("Current target widget coverage: ${triggeredWidgets}/${allTargetStaticWidgets.size}=${triggeredWidgets / allTargetStaticWidgets.size.toDouble()}")
        log.debug("Current target event coverage: $triggeredEventCount/${allTargetStaticEvents.size} = ${triggeredEventCount/allTargetStaticEvents.size.toDouble()}")
    }*/

    fun isOptionMenuOpen(currentState: State<*>): Boolean {
        val window = getAbstractState(currentState)!!.window
        if (window is OptionsMenu)
            return true
        return false
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
        activity_TargetComponent_Map.filter { it.key != currentActivity }.forEach {
            if (it.value.size > 0)
                candidates.add(it.key)

        }
        return candidates
    }

    fun getNearestTargetActivityPaths_P1(currentState: State<*>): List<LinkedList<WindowTransition>> {
        //val activitiesWeights = arrayListOf<Pair<WindowTransition, Double>>()
        //get list of activities containing untriggered target widget
        val currentActivity = stateActivityMapping[currentState]!!
        val candidateActivities = getCandidateActivity_P1(currentActivity)
        val possibleTransitions = ArrayList<LinkedList<WindowTransition>>()
        candidateActivities.forEach {
            //findPathActivityToActivty(currentActivity, it, LinkedList(), possibleTransitions)
        }
        if (possibleTransitions.isEmpty())
            return emptyList()
        val sortedTransitions = possibleTransitions.sortedBy { it.size }
        val minTran = sortedTransitions.first()
        val nearestTransitions = sortedTransitions.filter { it.size == minTran.size }
        return nearestTransitions
        //calculate weight of each window transition to untriggered target widget
        //        allActivityActivityTransitions.forEach {
        //            if (it.source == currentActivity)
        //            {
        //                val target = it.target
        //                if (activity_TargetComponent_Map.containsKey(target))
        //                {
        //                    val targetWidgets = activity_TargetComponent_Map[target]
        //                    val untriggeredWidgets = targetWidgets!!.filter { untriggeredWidgets.contains(it) }
        //                    val weight = 1 - 1/(untriggeredWidgets.size.toDouble())
        //                    activitiesWeights.add(Pair(it,weight))
        //                }
        //
        //            }
        //        }
        //        //sort descendent by weight
        //        val candidate = activitiesWeights.sortedByDescending { it.second }.firstOrNull()
        //
        //        return candidate?.first?:null
    }

    //endregion

    //region phase2
    var remainPhaseStateCount: Int = 0
    val notFullyCoveredTargetEvents = HashMap<Input, Int>() //Event - number of exercise

    fun resetIneffectiveActionCounter() {
        updateMethodCovFromLastChangeCount = 0
    }


    fun validateEvent(e: Input, currentState: State<*>): List<AbstractAction> {
        if (e.eventType == EventType.implicit_rotate_event && !appRotationSupport) {
            if (allTargetInputs.contains(e)) {
                allTargetInputs.remove(e)
            }
            return emptyList()
        }
        val currentAbstractState = getAbstractState(currentState)!!
        val availableAbstractActions = currentAbstractState.inputMappings.filter { it.value.contains(e) }.map { it.key }
        val validatedAbstractActions = ArrayList<AbstractAction>()
        availableAbstractActions.forEach {
            if (!it.isWidgetAction()) {
                validatedAbstractActions.add(it)
            } else {
                if (it.attributeValuationMap!!.getGUIWidgets(currentState).isNotEmpty()) {
                    validatedAbstractActions.add(it)
                }
            }
        }
        return validatedAbstractActions
    }


    var numberOfContinuousRandomAction: Int = 0
    fun canExerciseTargetActivty(): Boolean {
        //TODO: Implement it before using
        return true
    }


    //endregion


    fun isPressBackCanGoToHomescreen(currentAbstractState: AbstractState): Boolean {

        val pressBackEdges = DSTG.edges(currentAbstractState).filter {
            it.label.abstractAction.actionType == AbstractActionType.PRESS_BACK
        }
        val backToHomeScreen = pressBackEdges.find { it.destination != null && it.destination!!.data.isHomeScreen }
        return (backToHomeScreen != null)
    }

    fun isPressBackCanGoToHomescreen(currentState: State<*>): Boolean {
        val currentAbstractState = getAbstractState(currentState)
        if (currentAbstractState == null)
            return false
        val pressBackEdges = DSTG.edges(currentAbstractState).filter {
            it.label.abstractAction.actionType == AbstractActionType.PRESS_BACK
        }
        val backToHomeScreen = pressBackEdges.find { it.destination != null && it.destination!!.data.isHomeScreen }
        return (backToHomeScreen != null)
    }

    fun getRuntimeWidgets(attributeValuationMap: AttributeValuationMap, widgetAbstractState: AbstractState, currentState: State<*>): List<Widget> {
        val allGUIWidgets = attributeValuationMap.getGUIWidgets(currentState)
        if (allGUIWidgets.isEmpty()) {
            //try get the same static widget
            val abstractState = getAbstractState(currentState)
            if (abstractState == widgetAbstractState)
                return allGUIWidgets
            if (widgetAbstractState.EWTGWidgetMapping.containsKey(attributeValuationMap) && abstractState != null) {
                val staticWidgets = widgetAbstractState.EWTGWidgetMapping[attributeValuationMap]!!
                val similarWidgetGroups = abstractState.EWTGWidgetMapping.filter { it.value == staticWidgets }.map { it.key }
                return similarWidgetGroups.map { it.getGUIWidgets(currentState) }.flatten()
            }
        }
        return allGUIWidgets
    }

    //endregion
    init {

    }

    //region statical analysis helper
    private fun isContextMenu(source: String): Boolean {
        if (source == "android.view.ContextMenu")
            return true
        return false
    }

    private fun isOptionMenu(source: String): Boolean {
        if (source == "android.view.Menu")
            return true
        return false
    }


    private fun getOptionMenuActivity(EWTGWidget: EWTGWidget): String {
        allActivityOptionMenuItems.forEach {
            if (it.value.contains(EWTGWidget)) {
                return it.key
            }
        }
        return ""
    }


    private fun isDialog(source: String) = allDialogOwners.filter { it.value.contains(source) }.size > 0
    //endregion

    fun getAppName() = appName

    fun getStateActivity(state: State<*>): String {
        if (stateActivityMapping.contains(state))
            return stateActivityMapping[state]!!
        else
            return ""
    }

    fun getAbstractState(state: State<*>): AbstractState? {
        return AbstractStateManager.instance.getAbstractState(state)
    }

    //region readJSONFile

    val methodTermsHashMap = HashMap<String, HashMap<String, Long>>()
    val windowTermsHashMap = HashMap<Window, HashMap<String, Long>>()
    val windowHandlersHashMap = HashMap<Window, Set<String>>()
    val activityAlias = HashMap<String, String>()
    val modifiedMethodTopCallersMap = HashMap<String, Set<String>>()


    fun getAppModelFile(): Path? {
        if (!Files.exists(resourceDir)) {
            ATUAMF.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val appModelFile = getAppModelFile(appName, resourceDir)
            if (appModelFile != null)
                return appModelFile
            else {
                ATUAMF.log.warn("Provided directory ($resourceDir) does not contain " +
                        "the corresponding instrumentation file.")
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
            ATUAMF.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val textInputFile = getTextInputFile(appName, resourceDir)
            if (textInputFile != null)
                return textInputFile
            else {
                ATUAMF.log.warn("Provided directory ($resourceDir) does not contain " +
                        "the corresponding text input file.")
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
            ATUAMF.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val configurationFile = getDeviceConfigurationFile(appName, resourceDir)
            if (configurationFile != null)
                return configurationFile
            else {
                ATUAMF.log.warn("Provided directory ($resourceDir) does not contain " +
                        "the corresponding configuration file.")
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
            ATUAMF.log.warn("Provided Dir does not exist: $resourceDir.")
            return null

        } else {
            val intentModelFile = getIntentModelFile(appName, resourceDir)
            if (intentModelFile != null)
                return intentModelFile
            else {
                ATUAMF.log.warn("Provided directory ($resourceDir) does not contain " +
                        "the corresponding intent model file.")
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

    private fun addWidgetToActivtity_TargetWidget_Map(activity: String, event: Input) {
        if (!activity_TargetComponent_Map.containsKey(activity)) {
            activity_TargetComponent_Map[activity] = ArrayList()
        }
        if (!activity_TargetComponent_Map[activity]!!.contains(event)) {
            activity_TargetComponent_Map[activity]!!.add(event)
        }
    }

    private fun haveContextMenuOnItsWidget(currentActivity: String?): Boolean {
        val wtgNode = WindowManager.instance.updatedModelWindows.find { it.classType == currentActivity }
        if (wtgNode == null)
            return false
        return wtg.haveContextMenu(wtgNode)
    }

    //endregion

    fun produceTargetWidgetReport(context: ExplorationContext<*, *, *>) {
        val sb = StringBuilder()
        sb.appendln("Statements;${statementMF!!.statementInstrumentationMap.size}")
        sb.appendln("Methods;${statementMF!!.methodInstrumentationMap.size}")
        sb.appendln("ModifiedMethods;${statementMF!!.modMethodInstrumentationMap.size}")
        sb.appendln("ModifiedMethodsStatements;${
        statementMF!!.statementMethodInstrumentationMap.filter { statementMF!!.modMethodInstrumentationMap.contains(it.value) }.size
        } ")
        sb.appendln("CoveredStatements;${statementMF!!.executedStatementsMap.size}")
        sb.appendln("CoveredMethods;${statementMF!!.executedMethodsMap.size}")
        sb.appendln("CoveredModifiedMethods;${statementMF!!.executedModifiedMethodsMap.size}")
        sb.appendln("CoveredModifiedMethodsStatements;${statementMF!!.executedModifiedMethodStatementsMap.size}")
        sb.appendln("ListCoveredModifiedMethods;")
        if (statementMF!!.executedModifiedMethodsMap.isNotEmpty()) {
            val sortedMethods = statementMF!!.executedModifiedMethodsMap.entries
                    .sortedBy { it.value }
            val initialDate = sortedMethods.first().value
            sortedMethods
                    .forEach {
                        sb.appendln("${it.key};${statementMF!!.modMethodInstrumentationMap[it.key]};${Duration.between(initialDate.toInstant(), it.value.toInstant()).toMillis() / 1000}")
                    }

        }
        sb.appendln("EndOfList")
        sb.appendln("ListUnCoveredModifiedMethods;")
        statementMF!!.modMethodInstrumentationMap.filterNot { statementMF!!.executedModifiedMethodsMap.containsKey(it.key) }.forEach {
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
        sb.appendln("Unreached windows;")
        WindowManager.instance.updatedModelWindows.filterNot {
            it is Launcher
                    || it is OutOfApp || it is FakeWindow
        }.filter { node -> AbstractStateManager.instance.ABSTRACT_STATES.find { it.window == node && it !is VirtualAbstractState } == null }.forEach {
            sb.appendln(it.toString())
        }
        /*  sb.appendln("Unmatched widget: ${allTargetStaticWidgets.filter { it.mappedRuntimeWidgets.isEmpty() }.size}")
          allTargetStaticWidgets.forEach {
              if (it.mappedRuntimeWidgets.isEmpty())
              {
                  sb.appendln("${it.resourceIdName}-${it.className}-${it.widgetId} in ${it.activity}")
              }
          }*/

        val numberOfAppStates = AbstractStateManager.instance.ABSTRACT_STATES.size
        sb.appendln("NumberOfAppStates;$numberOfAppStates")

        val outputFile = context.model.config.baseDir.resolve(targetWidgetFileName)
        ATUAMF.log.info("Prepare writing triggered widgets report file: " +
                "\n- File name: ${outputFile.fileName}" +
                "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}")

        Files.write(outputFile, sb.lines())
        ATUAMF.log.info("Finished writing report in ${outputFile.fileName}")


        val sb2 = StringBuilder()
        sb2.appendln(ModelBackwardAdapter.instance.backwardEquivalentMapping.size)
        ModelBackwardAdapter.instance.backwardEquivalentMapping.forEach { newState, baseStates ->
            sb2.appendln("---")
            sb2.appendln(newState.abstractStateId)
            sb2.appendln(baseStates.size)
            baseStates.forEach {
                sb2.appendln(it.abstractStateId)
            }
        }
        if (reuseBaseModel) {
            val modelbackwardReport = context.model.config.baseDir.resolve("backwardEquivalenceReport.txt")
            ATUAMF.log.info("Prepare writing backward equivalence report file: " +
                    "\n- File name: ${modelbackwardReport.fileName}" +
                    "\n- Absolute path: ${modelbackwardReport.toAbsolutePath().fileName}")

            Files.write(modelbackwardReport, sb2.lines())
            ATUAMF.log.info("Finished writing report in ${modelbackwardReport.fileName}")
        }
    }

    //Widget override
    fun Widget.isInteractable(): Boolean = enabled && (isInputField || clickable || checked != null || longClickable || scrollable)

    fun getToolBarMoreOptions(currentState: State<*>): Widget? {
        currentState.widgets.filter { it.isVisible && it.contentDesc.contains("More options") }.forEach {
            if (Helper.hasParentWithType(it, currentState, "LinearLayoutCompat")) {
                return it
            }
        }
        return null
    }

    fun accumulateEventsDependency(): HashMap<Input, HashMap<String, Long>> {
        val result = HashMap<Input, HashMap<String, Long>>()
        allTargetInputs.forEach { event ->
            val eventDependency = HashMap<String, Long>()
            event.eventHandlers.forEach {
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

    fun getCandidateAction(currentState: State<*>, delay: Long, useCoordinator: Boolean): Pair<ExplorationAction, Widget?> {
        val currentAbstractState = getAbstractState(currentState)!!
        val actionableWidget = Helper.getActionableWidgetsWithoutKeyboard(currentState)
        val currentStateActionScores = actionScore.filter { (it.key.first == null || actionableWidget.any { w -> w.uid == it.key.first }) && it.value.containsKey(currentState.uid) }
                .map { Pair(it.key, it.value.get(currentState.uid)!!) }.toMap()
        if (currentStateActionScores.isEmpty())
            return Pair(ExplorationAction.pressBack(), null)
        var candidateAction: ExplorationAction? = null
        var candidateWidget: Widget? = null
        val excludedActions = ArrayList<Pair<UUID?, AbstractActionType>>()
        while (candidateAction == null) {
            val availableCurrentStateScores = currentStateActionScores.filter { !excludedActions.contains(it.key) }
            if (availableCurrentStateScores.isEmpty()) {
                break
            }
            val maxCurrentStateScore = if (Random.nextDouble() < 0.5) {
                availableCurrentStateScores.maxBy { it.value }!!.key
            } else {
                val pb = ProbabilityDistribution<Pair<UUID?, AbstractActionType>>(availableCurrentStateScores)
                pb.getRandomVariable()
            }
            if (maxCurrentStateScore.first != null) {
                val candidateWidgets = actionableWidget.filter { it.uid == maxCurrentStateScore.first }
                if (candidateWidgets.isEmpty())
                    return Pair(ExplorationAction.pressBack(), null)
                val widgetActions = candidateWidgets.map { w ->
                    Pair<Widget, List<ExplorationAction>>(w,
                            Helper.getAvailableActionsForWidget(w, currentState, delay, useCoordinator)
                                    .filter { normalizeActionType(it.name) == maxCurrentStateScore.second })
                }
                val candidateActions = widgetActions.filter { it.second.isNotEmpty() }
                if (candidateActions.isNotEmpty()) {
                    val candidate = candidateActions.random()
                    candidateWidget = candidate.first
                    candidateAction = candidate.second.random()
                    ExplorationTrace.widgetTargets.clear()
                    ExplorationTrace.widgetTargets.add(candidateWidget)
                } else
                    excludedActions.add(maxCurrentStateScore)
            } else {
                val action: ExplorationAction = when (maxCurrentStateScore.second) {
                    AbstractActionType.ROTATE_UI -> rotateUI(currentAbstractState)
                    AbstractActionType.PRESS_BACK -> ExplorationAction.pressBack()
                    AbstractActionType.CLOSE_KEYBOARD -> GlobalAction(ActionType.CloseKeyboard)
                    AbstractActionType.MINIMIZE_MAXIMIZE -> GlobalAction(ActionType.MinimizeMaximize)
                    AbstractActionType.PRESS_MENU -> GlobalAction(ActionType.PressMenu)
                    else -> ExplorationAction.pressBack()
                }
                return Pair(action, null)
            }
        }
        if (candidateAction == null) {
            return Pair(ExplorationAction.pressBack(), null)
        }
        //check candidate action
        return Pair(candidateAction, candidateWidget)
    }

    private fun rotateUI(currentAbstractState: AbstractState): ExplorationAction {
        if (currentAbstractState.rotation == Rotation.PORTRAIT) {
            return ExplorationAction.rotate(90)
        } else {
            return ExplorationAction.rotate(-90)
        }
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
        val allTargetInputs = ArrayList(allTargetInputs)

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
        AbstractStateManager.instance.getPotentialAbstractStates().forEach { appStateList.add(it) }

        //get all AppState's edges and appState's modified method
        val edges = ArrayList<Edge<AbstractState, AbstractTransition>>()
        appStateList.forEach { appState ->
            edges.addAll(DSTG.edges(appState).filter { it.label.isExplicit() || it.label.fromWTG })
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
            var appStateScore: Double = 0.0
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
        allTargetWindow_ModifiedMethods.filter { abstractStateProbabilityByWindow.containsKey(it.key) }.forEach { n, _ ->
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
                    val methods = modifiedMethodTopCallersMap.filter { it.value.contains(handler) }.map { it.key }
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

    companion object {

        @JvmStatic
        val log: Logger by lazy { LoggerFactory.getLogger(ATUAMF::class.java) }

        object RegressionStrategy : PropertyGroup() {
            val baseModelDir by stringType
            val use by booleanType
            val budgetScale by doubleType
            val manualInput by booleanType
            val manualIntent by booleanType
            val reuseBaseModel by booleanType
        }


    }
}

enum class MyStrategy {
    INITIALISATION,
    RANDOM_TARGET_WIDGET_SELECTION,
    SEARCH_FOR_TARGET_WIDGET,
    RANDOM_EXPLORATION,
    REACH_MORE_MODIFIED_METHOD
}

enum class Rotation {
    LANDSCAPE,
    PORTRAIT
}