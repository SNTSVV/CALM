package org.droidmate.exploration.strategy.atua.task

import kotlinx.coroutines.runBlocking
import org.atua.calm.ModelBackwardAdapter
import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.atua.modelFeatures.dstg.PredictedAbstractState
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.PathTraverser
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.GlobalAction
import org.droidmate.deviceInterface.exploration.isEnabled
import org.droidmate.exploration.actions.click
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.rotate
import org.droidmate.exploration.actions.setText
import org.droidmate.exploration.strategy.atua.ATUATestingStrategy
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList

open class GoToAnotherWindowTask constructor(
    autautMF: org.atua.modelFeatures.ATUAMF,
    atuaTestingStrategy: ATUATestingStrategy,
    delay: Long, useCoordinateClicks: Boolean
) : AbstractStrategyTask(atuaTestingStrategy, autautMF, delay, useCoordinateClicks) {

    private val DEFAULT_MAX_COST: Double = 25.0
    protected var maxCost: Double = DEFAULT_MAX_COST
    private var tryOpenNavigationBar: Boolean = false
    private var tryScroll: Boolean = false
    protected var mainTaskFinished: Boolean = false
    protected var prevState: State<*>? = null
    protected var prevAbState: AbstractState? = null
    protected var randomExplorationTask: RandomExplorationTask =
        RandomExplorationTask(this.atuaMF, atuaTestingStrategy, delay, useCoordinateClicks, true, 1)
    private val fillDataTask = PrepareContextTask(this.atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)

    var isFillingText: Boolean = false

    //protected var currentEdge: AbstractTransition?=null
    protected var expectedNextAbState: AbstractState? = null
    var currentPath: TransitionPath? = null
    val possiblePaths = ArrayList<TransitionPath>()
    var pathTraverser: PathTraverser? = null

    var destWindow: Window? = null
    var useInputTargetWindow: Boolean = false
    var retryTimes: Int = 0
    var isTarget: Boolean = false
    var saveBudget: Boolean = false

    val fillingDataActionList = Stack<Pair<ExplorationAction, Widget>>()

    init {
        randomExplorationTask.isPureRandom = true
    }

    override fun chooseRandomOption(currentState: State<*>) {
        val notIncludeResetPaths = possiblePaths.filter {
            it.path.values.all { it.abstractAction.actionType != AbstractActionType.RESET_APP }
        }
        if (notIncludeResetPaths.isEmpty())
            currentPath = possiblePaths.random()
        else {
            val notIncludeLaunchPaths = notIncludeResetPaths.filter {
                it.path.values.all { it.abstractAction.actionType != AbstractActionType.LAUNCH_APP }
            }
            if (notIncludeLaunchPaths.isEmpty())
                currentPath = notIncludeResetPaths.random()
            else
                currentPath = notIncludeLaunchPaths.random()
        }
        //  currentEdge = null
        pathTraverser = PathTraverser(currentPath!!)
        destWindow = currentPath!!.getFinalDestination().window
        possiblePaths.remove(currentPath!!)
        expectedNextAbState = currentPath!!.root
        mainTaskFinished = false
        isFillingText = false
        tryOpenNavigationBar = false
        tryScroll = false
    }

    var actionTryCount = 0
    val maxActionTryCount = 3*atuaStrategy.scaleFactor

    override fun isTaskEnd(currentState: State<*>): Boolean {

        /*if (atuaMF.prevAbstractStateRefinement > 0)
            return true*/
        if (pathTraverser == null)
            return true
        if (mainTaskFinished) {
            log.info("Failed to reach destination.")
            failedCount++
            return true
        }
        if (currentPath == null)
            return true
        if (pathTraverser!!.getCurrentTransition() == null)
            return true
        val currentAppState = atuaMF.getAbstractState(currentState)!!

        if (isWindowAsTarget && currentAppState.window == destWindow) {
            log.info("Reached destination.")
            succeededCount++
            return true
        }
        if (isFillingText)
            return false
        //if app reached the final destination

        if (pathTraverser!!.isEnded()) {
            if (currentAppState == currentPath!!.getFinalDestination()) {
                log.info("Reached destination.")
                succeededCount++
                return true
            }
        }
        //if currentNode is expectedNextNode
        if (isExploration) {
            if (destWindow==null || currentAppState.window == destWindow) {
                if (currentAppState.getUnExercisedActions(currentState, atuaMF).filter{it.isWidgetAction()}.isNotEmpty()) {
                    log.info("Reached destination.")
                    succeededCount++
                    return true
                }
            }
        }
        if (expectedNextAbState != null) {
            val lastTransition = pathTraverser!!.getCurrentTransition()!!
            if (!isReachExpectedState(currentState)) {
                val prevState = atuaMF.appPrevState!!
                val prevAppState = atuaMF.getAbstractState(prevState)
                val lastAbstractAction = lastTransition.abstractAction
                val abstractStateStacks = atuaMF.getAbstractStateStack()
//                log.debug("Fail to reach $expectedNextAbState")
                addIncorrectPath(currentAppState)
                if (pathTraverser!!.transitionPath.goal.isNotEmpty()) {
                    val goal = pathTraverser!!.transitionPath.goal
                    if (currentAppState.getAvailableInputs().intersect(goal).isNotEmpty()) {
                        log.info("Reached destination.")
                        succeededCount++
                        return true
                    }
                }
                val nextAbstractTransition = pathTraverser!!.getNextTransition()
                val pathType = pathTraverser!!.transitionPath.pathType
                if (nextAbstractTransition!=null) {
                    if (nextAbstractTransition.source.window == currentAppState.window) {
                        if (pathTraverser!!.canContinue(currentAppState)) {
                            return false
                        }
                    }
                }
                val transitionPaths = ArrayList<TransitionPath>()
                val finalTarget = if (currentPath!!.getFinalDestination() !is PredictedAbstractState) {
                    if (AbstractStateManager.INSTANCE.ABSTRACT_STATES.contains(currentPath!!.getFinalDestination()))
                        currentPath!!.getFinalDestination()
                    else
                        AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.hashCode == currentPath!!.getFinalDestination().hashCode } }
                else
                    AbstractStateManager.INSTANCE.getVirtualAbstractState(currentPath!!.getFinalDestination().window)
//                val minCost = currentPath!!.cost(pathTraverser!!.latestEdgeId!!)
                val minCost = currentPath!!.cost()
                if (finalTarget != null) {
                    val pathConstraint = HashMap<PathConstraint,Boolean>()
                    pathConstraint.put(PathConstraint.INCLUDE_RESET,includeResetAction)
                    ProbabilityBasedPathFinder.findPathToTargetComponent(
                        currentState = currentState,
                        root = currentAppState,
                        finalTargets = listOf(finalTarget),
                        foundPaths = transitionPaths,
                        shortest = true,
                        pathCountLimitation = 1,
                        autautMF = atuaMF,
                        pathType = currentPath!!.pathType,
                        goalsByTarget = mapOf(Pair(finalTarget, pathTraverser!!.transitionPath.goal)),
                        windowAsTarget = (finalTarget is VirtualAbstractState && pathTraverser!!.transitionPath.goal.isEmpty()),
                        maxCost = minCost,
                        abandonedAppStates = emptyList(),
                        constraints = pathConstraint
                    )
                    if (transitionPaths.any { it.path.values.map { it.abstractAction }.equals(currentPath!!.path.values.map { it.abstractAction }) }) {
                        transitionPaths.removeIf {
                            it.path.values.map { it.abstractAction }.equals(currentPath!!.path.values.map { it.abstractAction })
                        }
                    }
                }
                // Try another path if current state is not target node
                if (lastAbstractAction.isWebViewAction()
                    && lastAbstractAction.actionType!=AbstractActionType.SWIPE && transitionPaths.isEmpty() &&
                    (pathType ==PathFindingHelper.PathType.WIDGET_AS_TARGET || pathType ==PathFindingHelper.PathType.WTG)
                ) {
                    if (actionTryCount < maxActionTryCount && lastAbstractAction.attributeValuationMap!!.getGUIWidgets(currentState,currentAppState.window).isNotEmpty()) {
                        pathTraverser!!.latestEdgeId = pathTraverser!!.latestEdgeId!!-1
                        actionTryCount++
                        return false
                    }
                }
                if (lastAbstractAction.isWidgetAction()
                    && lastAbstractAction.actionType == AbstractActionType.SWIPE
                    && prevAppState != currentAppState && transitionPaths.isEmpty()) {
                    if (actionTryCount < maxActionTryCount && lastAbstractAction.attributeValuationMap!!.getGUIWidgets(currentState,currentAppState.window).isNotEmpty()) {
                        log.info("Retry Swipe action on ${lastAbstractAction.attributeValuationMap!!}")
                        pathTraverser!!.latestEdgeId = pathTraverser!!.latestEdgeId!!-1
                        actionTryCount++
                        return false
                    }
                }
                actionTryCount = 0
                expectedNextAbState = pathTraverser!!.getCurrentTransition()?.dest
                if (transitionPaths.isNotEmpty()) {
                    log.info("Change path to the target")
                    possiblePaths.clear()
                    possiblePaths.addAll(transitionPaths)
                    initialize(currentState)
                    return false
                }
                return reroutePath(currentState, currentAppState)
            } else {
                actionTryCount = 0
                expectedNextAbState = lastTransition.dest
                if (pathTraverser!!.isEnded()) {
                    log.info("Reached destination.")
                    succeededCount++
                    return true
                }
                if (pathTraverser!!.transitionPath.goal.isNotEmpty()) {
                    val goal = pathTraverser!!.transitionPath.goal
                    if (currentAppState.getAvailableInputs().intersect(goal).isNotEmpty()) {
                        log.info("Reached destination.")
                        succeededCount++
                        return true
                    }
                }
                if (expectedNextAbState is VirtualAbstractState) {
                    return reroutePath(currentState, currentAppState,true)
                }
                if (expectedNextAbState is PredictedAbstractState) {
                    val nextAction = pathTraverser!!.getNextTransition()?.abstractAction
                    if (nextAction != null) {
                        val exisitingAbstractTransition =
                            currentAppState.abstractTransitions.find {
                                it.abstractAction == nextAction && it.isExplicit()}
                        if (exisitingAbstractTransition != null) {
                            return reroutePath(currentState, currentAppState,true)
                        }
                    }
                }
                return false
            }
        } else {
            //something wrong, should end task
            log.debug("Fail to reach destination.")
            failedCount++
            return true
        }
    }

    private fun reroutePath(
        currentState: State<*>,
        currentAppState: AbstractState,
        continueMode: Boolean = false
    ): Boolean {
        log.debug("Reidentify paths to the destination.")
        val transitionPaths = ArrayList<TransitionPath>()
        if (continueMode ) {
            val finalTarget = if (currentPath!!.getFinalDestination() !is PredictedAbstractState) {
                if (AbstractStateManager.INSTANCE.ABSTRACT_STATES.contains(currentPath!!.getFinalDestination()))
                    currentPath!!.getFinalDestination()
                else
                    AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.hashCode == currentPath!!.getFinalDestination().hashCode } }
            else
                AbstractStateManager.INSTANCE.getVirtualAbstractState(currentPath!!.getFinalDestination().window)
            val minCost = currentPath!!.cost(pathTraverser!!.latestEdgeId!!)
            if (finalTarget != null) {
                val pathConstraints = HashMap<PathConstraint, Boolean>()
                pathConstraints.put(PathConstraint.INCLUDE_RESET, false)
                ProbabilityBasedPathFinder.findPathToTargetComponent(
                    currentState = currentState,
                    root = currentAppState,
                    finalTargets = listOf(finalTarget),
                    foundPaths = transitionPaths,
                    shortest = true,
                    pathCountLimitation = 1,
                    autautMF = atuaMF,
                    pathType = currentPath!!.pathType,
                    goalsByTarget = mapOf(Pair(finalTarget, currentPath!!.goal)),
                    windowAsTarget = (currentPath!!.destination is VirtualAbstractState && currentPath!!.goal.isEmpty()),
                    maxCost = minCost,
                    abandonedAppStates = emptyList(),
                    constraints = pathConstraints
                    )
            }
            if (transitionPaths.any { it.path.values.map { it.abstractAction }.equals(currentPath!!.path.values.map { it.abstractAction }) }) {
                transitionPaths.removeIf {
                    it.path.values.map { it.abstractAction }.equals(currentPath!!.path.values.map { it.abstractAction })
                }
            }
            if (transitionPaths.isNotEmpty()) {
                log.info("Change path to the target")
                possiblePaths.clear()
                possiblePaths.addAll(transitionPaths)
                initialize(currentState)
                return false
            }
//            AbstractStateManager.INSTANCE.unreachableAbstractState.add(finalTarget!!)
        }
        val retryBudget = if (includeResetAction) {
            (10 * atuaStrategy.scaleFactor).toInt()
        } else
            (10 * atuaStrategy.scaleFactor).toInt()
        if (retryTimes < retryBudget) {
            retryTimes += 1
            log.debug("Retry-time: $retryTimes")
            identifyPossiblePaths(currentState, true)
            if (possiblePaths.isNotEmpty() ) {
                val minCost = currentPath!!.cost(pathTraverser!!.latestEdgeId!! + 1)
                if (saveBudget && !possiblePaths.any { it.cost() <= minCost }) {
                    log.debug("Fail to reach destination.")
                    failedCount
                    return true
                }
                initialize(currentState)
                return false
            }
        }
        /*else if (currentPath!!.pathType != PathFindingHelper.PathType.PARTIAL_TRACE && currentPath!!.pathType != PathFindingHelper.PathType.FULLTRACE) {
            retryTimes += 1
            initPossiblePaths(currentState, true,PathFindingHelper.PathType.PARTIAL_TRACE)
            if (possiblePaths.isNotEmpty() && possiblePaths.any { it.pathType == PathFindingHelper.PathType.PARTIAL_TRACE || it.pathType == PathFindingHelper.PathType.FULLTRACE }) {
                initialize(currentState)
                log.debug(" Paths is not empty")
                return false
            }
        } */
        /*else if (currentPath!!.pathType != PathFindingHelper.PathType.FULLTRACE && includeResetAction) {
            retryTimes += 1
            identifyPossiblePaths(currentState, true,PathFindingHelper.PathType.FULLTRACE)
            if (possiblePaths.isNotEmpty()) {
                initialize(currentState)
                log.debug(" Paths is not empty")
                return false
            }
        }*/
        log.debug("Fail to reach destination.")
        failedCount++
        return true
    }

    fun isReachExpectedState(currentState: State<*>): Boolean {
        var reached = false
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        var expectedAbstractState = pathTraverser!!.getCurrentTransition()!!.dest
        if (expectedAbstractState.hashCode == currentAppState.hashCode)
            return true
        if (pathTraverser!!.isEnded() && expectedAbstractState.isRequestRuntimePermissionDialogBox)
            return true
        if (expectedAbstractState.isRequestRuntimePermissionDialogBox) {
            pathTraverser!!.next()
            expectedNextAbState = pathTraverser!!.getCurrentTransition()!!.dest
            expectedAbstractState = expectedNextAbState!!
        }
        if (expectedAbstractState.ignored) {
            pathTraverser!!.next()
            expectedNextAbState = pathTraverser!!.getCurrentTransition()!!.dest
            expectedAbstractState = expectedNextAbState!!
        }
        if (expectedAbstractState == currentAppState || expectedAbstractState.hashCode == currentAppState.hashCode)
            return true
        if (pathTraverser!!.isEnded()) {
            if (expectedAbstractState.window == currentAppState.window
            ) {
                val currentInputs = currentAppState.getAvailableInputs()
                if  (isWindowAsTarget || pathTraverser!!.transitionPath.goal.isEmpty() || currentInputs.intersect(pathTraverser!!.transitionPath.goal)
                        .isNotEmpty()
                ) {
                    return true
                }
            }
            return false
        }
        if (expectedAbstractState is PredictedAbstractState) {
            if (pathTraverser!!.transitionPath.goal.isNotEmpty()) {
                val goal = pathTraverser!!.transitionPath.goal
                if (currentAppState.getAvailableInputs().intersect(goal).isNotEmpty())
                    return true
            }
        }
        val pathType = pathTraverser!!.transitionPath.pathType
        val nextAbstractTransition = pathTraverser!!.transitionPath.path[pathTraverser!!.latestEdgeId!! + 1]
        if (nextAbstractTransition != null
            && nextAbstractTransition.dest is PredictedAbstractState) {
            if (nextAbstractTransition.source.window == currentAppState.window) {
                if (pathTraverser!!.canContinue(currentAppState)) {
                    return true
                }
            }
        }
        val tmpPathTraverser = PathTraverser(currentPath!!)
        tmpPathTraverser.latestEdgeId = pathTraverser!!.latestEdgeId
        while (!tmpPathTraverser.isEnded()) {
            val currentTransition = tmpPathTraverser.getCurrentTransition()
            if (currentTransition == null)
                break
            val expectedAbstractState1 = currentTransition.dest
            if (expectedAbstractState1!!.ignored) {
                tmpPathTraverser.next()
                continue
            }
            if (expectedAbstractState1!!.window != currentAppState!!.window) {
                tmpPathTraverser.next()
                continue
            }
            if (expectedAbstractState1.hashCode == currentAppState.hashCode) {
                reached = true
                break
            }
            if (expectedAbstractState1 is VirtualAbstractState) {
                if (expectedAbstractState.window == currentAppState.window) {
                    reached = true
                    break
                }
            } else if (expectedAbstractState1 is PredictedAbstractState) {
                if (tmpPathTraverser.canContinue(currentAppState)) {
                    reached = true
                    break
                }
            }
            tmpPathTraverser.next()
        }
        if (reached) {
            expectedNextAbState = tmpPathTraverser.getCurrentTransition()!!.dest
            pathTraverser!!.latestEdgeId = tmpPathTraverser.latestEdgeId
        }
        return reached
    }

    override fun hasAnotherOption(currentState: State<*>): Boolean {
        if (currentPath!!.root == atuaMF.getAbstractState(currentState)!!
            && possiblePaths.size > 0
        ) {//still in the source activity
            log.debug("Can change to another option.")
            return true
        }
        return false
    }

    override fun initialize(currentState: State<*>) {
        randomExplorationTask!!.fillingData = false
        randomExplorationTask!!.backAction = true
        chooseRandomOption(currentState)
        atuaStrategy.phaseStrategy.fullControl = true
    }

    override fun reset() {
        possiblePaths.clear()
        includePressbackAction = true
        destWindow = null
        currentPath = null
        useInputTargetWindow = false
        includeResetAction = true
        isExploration = false
        useTrace = true
        saveBudget = false
        maxCost = DEFAULT_MAX_COST
    }

    var useTrace: Boolean = true

    override fun isAvailable(currentState: State<*>): Boolean {
        reset()
        isExploration = true
        isWindowAsTarget = false
        useTrace = false
        identifyPossiblePaths(currentState)
        if (possiblePaths.size > 0) {
            return true
        }
        return false
    }

    var isWindowAsTarget: Boolean = false

    open fun isAvailable(
        currentState: State<*>,
        destWindow: Window,
        isWindowAsTarget: Boolean = false,
        includePressback: Boolean = true,
        includeResetApp: Boolean = true,
        isExploration: Boolean = true,
        maxCost: Double = DEFAULT_MAX_COST
    ): Boolean {
        log.info("Checking if there is any path to $destWindow")
        reset()
        this.isWindowAsTarget = isWindowAsTarget
        this.includePressbackAction = includePressback
        this.destWindow = destWindow
        this.useInputTargetWindow = true
        this.includeResetAction = includeResetApp
        this.isExploration = isExploration
        this.maxCost = maxCost
        identifyPossiblePaths(currentState)
        if (possiblePaths.size > 0) {
            return true
        }

/*       if (this.destWindow is WTGDialogNode || this.destWindow is WTGOptionsMenuNode) {
            val newTarget = WTGActivityNode.allNodes.find { it.classType == this.destWindow!!.classType || it.activityClass == this.destWindow!!.activityClass }
            if (newTarget == null || abstractState.window == newTarget)
                return false
            if (AbstractStateManager.instance.ABSTRACT_STATES.filter { it.window == newTarget }.map { it.getUnExercisedActions(null).size }.any { it > 0 } )
            {
                this.destWindow = newTarget
                initPossiblePaths(currentState)
                if (possiblePaths.size > 0) {
                    return true
                }
            }
        }*/
        return false
    }

    var includePressbackAction = true
    var includeResetAction = true
    var isExploration = true

    open protected fun identifyPossiblePaths(currentState: State<*>, continueMode: Boolean = false, pathType: PathFindingHelper.PathType=PathFindingHelper.PathType.WIDGET_AS_TARGET) {
        possiblePaths.clear()
        var nextPathType = pathType
        /*var nextPathType = if (currentPath == null)
                PathFindingHelper.PathType.NORMAL
        else
            computeNextPathType(currentPath!!.pathType,includeResetAction)*/
        val pathConstraints = HashMap<PathConstraint,Boolean>()
        pathConstraints.put(PathConstraint.INCLUDE_RESET,includeResetAction)
        pathConstraints.put(PathConstraint.INCLUDE_LAUNCH,true)
        if (useInputTargetWindow && destWindow != null) {
            while (possiblePaths.isEmpty()) {
                if (nextPathType == PathFindingHelper.PathType.WTG) {
                    pathConstraints.put(PathConstraint.INCLUDE_WTG,true)
                } else {
                    pathConstraints.put(PathConstraint.INCLUDE_WTG,false)
                }
                possiblePaths.addAll(
                    atuaStrategy.phaseStrategy.getPathsToWindowToExplore(
                        currentState =  currentState,
                        targetWindow =  destWindow!!,
                        pathType =  nextPathType,
                        explore =  isExploration || !isWindowAsTarget,
                        maxCost = maxCost,
                        pathConstraints = pathConstraints
                    )
                )
                if (computeNextPathType(nextPathType, includeResetAction) == PathFindingHelper.PathType.WIDGET_AS_TARGET)
                    break
                nextPathType = computeNextPathType(nextPathType, includeResetAction)
            }
        } else {
            while (possiblePaths.isEmpty()) {
                if (nextPathType == PathFindingHelper.PathType.WTG) {
                    pathConstraints.put(PathConstraint.INCLUDE_WTG,true)
                } else {
                    pathConstraints.put(PathConstraint.INCLUDE_WTG,false)
                }
                possiblePaths.addAll(atuaStrategy.phaseStrategy.getPathsToExploreStates(
                    currentState = currentState,
                    pathType = nextPathType,
                    maxCost = maxCost,
                    pathConstraints = pathConstraints))
                if (computeNextPathType(nextPathType, includeResetAction) == PathFindingHelper.PathType.WIDGET_AS_TARGET)
                    break
                nextPathType = computeNextPathType(nextPathType, includeResetAction)
            }

        }
    }

    fun computeNextPathType(
        pathType: PathFindingHelper.PathType,
        includeResetApp: Boolean
    ): PathFindingHelper.PathType {
        return when (pathType) {
//            PathFindingHelper.PathType.WIDGET_AS_TARGET -> PathFindingHelper.PathType.NORMAL
            // PathFindingHelper.PathType.NORMAL -> PathFindingHelper.PathType.WTG
//            PathFindingHelper.PathType.PARTIAL_TRACE -> PathFindingHelper.PathType.WTG
            /*PathFindingHelper.PathType.NORMAL_RESET -> PathFindingHelper.PathType.WIDGET_AS_TARGET_RESET
            PathFindingHelper.PathType.WIDGET_AS_TARGET_RESET -> PathFindingHelper.PathType.WTG
            */
            /*PathFindingHelper.PathType.WTG ->
                if (useTrace && includeResetApp)
                    PathFindingHelper.PathType.FULLTRACE
                else
                    PathFindingHelper.PathType.NORMAL
            PathFindingHelper.PathType.FULLTRACE -> PathFindingHelper.PathType.NORMAL*/
            PathFindingHelper.PathType.WIDGET_AS_TARGET ->
                if (isWindowAsTarget)
                    PathFindingHelper.PathType.WTG
                else
                    PathFindingHelper.PathType.WIDGET_AS_TARGET
            PathFindingHelper.PathType.WTG -> PathFindingHelper.PathType.WIDGET_AS_TARGET
            else -> PathFindingHelper.PathType.WIDGET_AS_TARGET
        }
    }

    fun chooseWidgets1(currentState: State<*>, nextTransition: AbstractTransition): List<Widget> {
        val widgetGroup = nextTransition.abstractAction.attributeValuationMap
        if (widgetGroup == null) {
            return emptyList()
        } else {
            val guiWidgets: List<Widget> = getGUIWidgetsByAVM(widgetGroup, currentState)
            if (guiWidgets.isEmpty()) {
                val inputs = nextTransition.source.getInputsByAbstractAction(nextTransition.abstractAction)
                val guiWidgets = ArrayList<Widget>()
                inputs.forEach { input ->
                    val ewtgWidget = input.widget
                    if (ewtgWidget != null) {
                        val ewtgWidgetByGUiWidget = WindowManager.instance.guiWidgetEWTGWidgetMappingByWindow[nextTransition.source.window]!!
                        val possibleGuiWidgets = ewtgWidgetByGUiWidget.filter { it.value == ewtgWidget }.keys
                        guiWidgets.addAll(possibleGuiWidgets)
                    }
                }
                when (nextTransition.abstractAction.actionType) {
                    AbstractActionType.CLICK -> guiWidgets.removeIf { !it.clickable }
                    AbstractActionType.LONGCLICK -> guiWidgets.removeIf { !it.longClickable }
                    AbstractActionType.SWIPE -> guiWidgets.removeIf {
                        !Helper.isScrollableWidget(it)
                    }
                    AbstractActionType.ITEM_CLICK -> guiWidgets.removeIf { !it.hasClickableDescendant }
                    AbstractActionType.ITEM_LONGCLICK -> guiWidgets.removeIf {  !Helper.haveLongClickableChild(currentState.widgets,it)}
                    AbstractActionType.ITEM_SELECTED -> guiWidgets.removeIf { !it.clickable }
                    AbstractActionType.TEXT_INSERT -> guiWidgets.removeIf { !it.isInputField }
                }
                return guiWidgets
            } else
                return guiWidgets
        }
    }

    private fun getGUIWidgetsByAVM(avm: AttributeValuationMap, currentState: State<*>): List<Widget> {
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        val widgets = ArrayList<Widget>()
        widgets.addAll(atuaMF.getRuntimeWidgets(avm, currentAbstractState, currentState))
//        if (widgets.isEmpty()) {
//            val staticWidget = currentAbstractState.EWTGWidgetMapping[avm]
//            if (staticWidget == null)
//                return emptyList()
//            val correspondentWidgetGroups = currentAbstractState.EWTGWidgetMapping.filter { it.value == staticWidget }
//            widgets.addAll(correspondentWidgetGroups.map { atuaMF.getRuntimeWidgets(it.key,currentAbstractState,currentState) }.flatten())
//        }
        return widgets
    }

    open fun increaseExecutedCount() {
        executedCount++
    }

    override fun chooseAction(currentState: State<*>): ExplorationAction? {
        increaseExecutedCount()
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        if (pathTraverser!!.getCurrentTransition() != null)
            expectedNextAbState = pathTraverser!!.getCurrentTransition()!!.dest
        else
            expectedNextAbState = currentAbstractState
        if (currentExtraTask != null)
            return currentExtraTask!!.chooseAction(currentState)
        /*if (expectedNextAbState == null) {
            mainTaskFinished = true
            return randomExplorationTask.chooseAction(currentState)
        }*/
        log.info("Path type: ${pathTraverser!!.transitionPath.pathType}")
        var nextAbstractState = expectedNextAbState

        if (currentAbstractState.isOpeningKeyboard && !expectedNextAbState!!.isOpeningKeyboard) {
            return GlobalAction(actionType = ActionType.CloseKeyboard)
        }
        prevState = currentState
        if (isFillingText) {
            if (fillingDataActionList.isEmpty()) {
                isFillingText = false
                return executeCurrentEdgeAction(
                    currentState,
                    pathTraverser!!.getCurrentTransition()!!,
                    currentAbstractState
                )
            } else {
                var actionInfo = fillingDataActionList.pop()
                while (!currentState.widgets.contains(actionInfo.second)) {
                    if (fillingDataActionList.empty()) {
                        actionInfo = null
                        break
                    }
                    actionInfo = fillingDataActionList.pop()
                }
                if (actionInfo != null) {
                    ExplorationTrace.widgetTargets.add(actionInfo.second)
                    return actionInfo.first
                } else {
                    isFillingText = false
                    return executeCurrentEdgeAction(
                        currentState,
                        pathTraverser!!.getCurrentTransition()!!,
                        currentAbstractState
                    )
                }
            }
        }
        prevAbState = expectedNextAbState
        if (currentPath == null || expectedNextAbState == null)
            return randomExplorationTask.chooseAction(currentState)
        log.info("Destination: ${currentPath!!.getFinalDestination()}")
        if (expectedNextAbState!!.window == currentAbstractState.window) {
            if (expectedNextAbState!!.rotation != currentAbstractState.rotation) {
                if (currentAbstractState.rotation == org.atua.modelFeatures.Rotation.LANDSCAPE) {
                    return ExplorationAction.rotate(-90)
                } else {
                    return ExplorationAction.rotate(90)
                }
            }
            if (!expectedNextAbState!!.isOpeningKeyboard && currentAbstractState.isOpeningKeyboard) {
                return GlobalAction(ActionType.CloseKeyboard)
            }
            val nextTransition = pathTraverser!!.next()
            if (nextTransition != null) {
                nextAbstractState = nextTransition!!.dest
                expectedNextAbState = nextAbstractState
                log.info("Next action: ${nextTransition.abstractAction}")
                log.info("Expected state: ${nextAbstractState}")
                //log.info("Event: ${currentEdge!!.label.abstractAction.actionName} on ${currentEdge!!.label.abstractAction.widgetGroup}")
                //Fill text input (if required)
                //TODO Need save swipe action data
                if (pathTraverser!!.transitionPath.pathType != PathFindingHelper.PathType.FULLTRACE
                    && pathTraverser!!.transitionPath.pathType != PathFindingHelper.PathType.PARTIAL_TRACE
                ) {
                    if (nextTransition.userInputs.isNotEmpty()) {
                        val inputData = nextTransition!!.userInputs.random()
                        inputData.forEach {
                            val inputWidget = currentState.visibleTargets.find { w -> it.key.equals(w.uid) }
                            if (inputWidget != null) {
                                if (inputWidget.isInputField) {
                                    if (inputWidget.text != it.value) {
                                        fillingDataActionList.add(
                                            Pair(
                                                inputWidget.setText(it.value, sendEnter = false),
                                                inputWidget
                                            )
                                        )
                                    }
                                } else if (inputWidget.checked.isEnabled()) {
                                    if (inputWidget.checked.toString() != it.value) {
                                        fillingDataActionList.add(Pair(inputWidget.click(), inputWidget))
                                    }
                                }
                            }
                        }
                    } /*else if (!nextTransition!!.abstractAction.isCheckableOrTextInput() ) {
                        if (fillDataTask.isAvailable(currentState,true) && random.nextBoolean()) {
                            fillDataTask.initialize(currentState)
                            fillDataTask.fillActions.entries.forEach {
                                fillingDataActionList.add(Pair(it.value,it.key))
                            }
                        }
                    }*/
                    if (fillingDataActionList.isNotEmpty()) {
                        isFillingText = true
                    }
                }
                if (isFillingText) {
                    val actionInfo = fillingDataActionList.pop()
                    ExplorationTrace.widgetTargets.add(actionInfo.second)
                    return actionInfo.first
                }
                return executeCurrentEdgeAction(currentState, nextTransition!!, currentAbstractState)
            } else {
                log.debug("Cannot get next transition.")
            }
        }
        mainTaskFinished = true
        log.debug("Cannot get target action, finish task.")
        return randomExplorationTask!!.chooseAction(currentState)
    }

    private fun executeCurrentEdgeAction(
        currentState: State<*>,
        nextTransition: AbstractTransition,
        currentAbstractState: AbstractState
    ): ExplorationAction? {
        val currentEdge = nextTransition
        if (currentEdge!!.abstractAction.actionType == AbstractActionType.PRESS_MENU) {
            return pressMenuOrClickMoreOption(currentState)
        }
        if (currentEdge!!.abstractAction.attributeValuationMap != null) {
            val widgets = chooseWidgets1(currentState, nextTransition)
            if (widgets.isNotEmpty()) {
                tryOpenNavigationBar = false
                tryScroll = false
                val candidates = runBlocking { getCandidates(widgets) }
                val chosenWidget = candidates[random.nextInt(candidates.size)]
                val actionName = currentEdge!!.abstractAction.actionType
                val actionData = if (currentEdge!!.data != null
                    && currentEdge!!.data != ""
                    && (currentEdge.abstractAction.actionType == AbstractActionType.TEXT_INSERT
                            || currentEdge.abstractAction.actionType == AbstractActionType.RANDOM_CLICK
                            || currentEdge.abstractAction.actionType == AbstractActionType.RANDOM_KEYBOARD
                            || currentEdge.abstractAction.actionType == AbstractActionType.ACTION_QUEUE
                            || currentEdge.abstractAction.actionType == AbstractActionType.UNKNOWN)) {
                    currentEdge!!.data
                } else {
                    currentEdge!!.abstractAction.extra
                }
                // atuaStrategy.phaseStrategy.registerTriggeredInputs(currentEdge!!.abstractAction,currentState)
                log.info("Widget: $chosenWidget")
                return chooseActionWithName(
                    actionName,
                    actionData,
                    chosenWidget,
                    currentState,
                    currentEdge!!.abstractAction
                )
                    ?: ExplorationAction.pressBack()
            } else {
                log.debug("Can not get target widget. Random exploration.")
                val widgets = chooseWidgets1(currentState, nextTransition)
                if (currentEdge.fromWTG && currentEdge.dest is VirtualAbstractState) {
                    pathTraverser!!.latestEdgeId = pathTraverser!!.latestEdgeId!! - 1
                } else {
                    pathTraverser!!.next()
                }
                return randomExplorationTask!!.chooseAction(currentState)
            }
        } else {
            tryOpenNavigationBar = false
            val action = currentEdge!!.abstractAction.actionType
            //val actionCondition = currentPath!!.edgeConditions[currentEdge!!]
            if (currentEdge!!.data != null && currentEdge!!.data != "") {
                return chooseActionWithName(
                    action,
                    currentEdge!!.data,
                    null,
                    currentState,
                    currentEdge!!.abstractAction
                )
                    ?: ExplorationAction.pressBack()
            } else {
                return chooseActionWithName(
                    action, currentEdge!!.abstractAction.extra
                        ?: "", null, currentState, currentEdge!!.abstractAction
                ) ?: ExplorationAction.pressBack()
            }
        }
    }

    var scrollAttempt = 0

    protected fun addIncorrectPath(currentAbstractState: AbstractState) {
        val corruptedEdge = if (currentAbstractState.window != expectedNextAbState!!.window)
            pathTraverser!!.getCurrentTransition()
        else
            pathTraverser!!.transitionPath.path[pathTraverser!!.latestEdgeId!! + 1]
        val lastTransition = pathTraverser!!.getCurrentTransition()!!
        if (lastTransition.dest is PredictedAbstractState)
        {
            lastTransition.source.abstractTransitions.remove(lastTransition)
            return
        }
        lastTransition.activated = false
        /*if (pathTraverser!!.transitionPath.pathType == PathFindingHelper.PathType.FULLTRACE) {
            if (lastTransition.interactions.isNotEmpty()) {
                lastTransition.activated = false
            }
        }*/
        if (lastTransition.modelVersion == ModelVersion.BASE ) {
//            lastTransition.activated = false
            val backwardTransitions = ModelBackwardAdapter.instance.backwardEquivalentAbstractTransitionMapping.get(lastTransition)
            backwardTransitions?.forEach { abstractTransition ->
                abstractTransition.activated = false
            }
        }
        else if (lastTransition.isImplicit) {
//            lastTransition.activated = false
            if (!lastTransition.dest.isSimlarAbstractState(currentAbstractState,0.8)) {
                AbstractStateManager.INSTANCE.ignoreImplicitDerivedTransition.add(
                    Triple(
                        lastTransition.source.window,
                        lastTransition.abstractAction,
                        lastTransition.dest.window
                    )
                )
            }
            if (currentAbstractState.window != lastTransition.dest.window) {
                var isIgnored = false
                if (!lastTransition.guardEnabled)
                    // this will be solved by refinement
                    isIgnored = false
                else
                    isIgnored = true
                if (isIgnored) {
                   /* AbstractStateManager.INSTANCE.ignoreImplicitDerivedTransition.add(
                        Triple(
                            lastTransition.source.window,
                            lastTransition.abstractAction,
                            lastTransition.dest.window
                        )
                    )*/
                    val abstractManager = AbstractStateManager.INSTANCE
                    val otherSameWindowAbStates = abstractManager.getSimilarAbstractStates(lastTransition.source, lastTransition)
                    otherSameWindowAbStates.forEach {
                        val dest = if (lastTransition.dest == lastTransition.source)
                            it
                        else
                            lastTransition.dest
                        val exisitingImplicitTransitions = it.abstractTransitions.filter {
                            it.abstractAction == lastTransition.abstractAction
                                    && it.modelVersion == ModelVersion.RUNNING
                                    && it.isImplicit
                                    && it.dest == dest
                        }
                        exisitingImplicitTransitions.forEach { abTransition ->
                            val edge = atuaMF.dstg.edge(abTransition.source, abTransition.dest, abTransition)
                            if (edge != null) {
                                atuaMF.dstg.remove(edge)
                            }
                            it.abstractTransitions.remove(abTransition)
                        }
                    }
                }
            }
        }
        PathFindingHelper.addDisablePathFromState(currentPath!!, corruptedEdge, lastTransition)
        /*if (currentEdge!=null) {

        }*/

    }

    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }

        var instance: GoToAnotherWindowTask? = null
        var executedCount: Int = 0
        var succeededCount: Int = 0
        var failedCount: Int = 0
        fun getInstance(
            regressionWatcher: org.atua.modelFeatures.ATUAMF,
            atuaTestingStrategy: ATUATestingStrategy,
            delay: Long, useCoordinateClicks: Boolean
        ): GoToAnotherWindowTask {
            if (instance == null) {
                instance = GoToAnotherWindowTask(regressionWatcher, atuaTestingStrategy, delay, useCoordinateClicks)
            }
            return instance!!
        }
    }
}