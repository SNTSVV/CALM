package org.droidmate.exploration.strategy

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.atua.modelFeatures.ATUAMF
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.*
import org.droidmate.exploration.strategy.manual.Logging
import org.droidmate.exploration.strategy.manual.getLogger
import org.droidmate.explorationModel.factory.AbstractModel
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import java.util.*
import kotlin.collections.HashMap
import kotlin.random.Random

@Suppress("unused")
object DefaultStrategies: Logging {
	override val log = getLogger()

	/**
	 * Terminate the exploration after a predefined elapsed time
	 */
	fun timeBasedTerminate(prio: Int, maxMs: Int) = object : AExplorationStrategy(){
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			val diff = eContext.getExplorationTimeInMs()
			log.info("remaining exploration time: ${"%.1f".format((maxMs-diff)/1000.0)}s")
			return maxMs in 1..diff
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			return ExplorationAction.terminateApp()
		}
	}

	/**
	 * Terminate the exploration after a predefined number of actions
	 */
	fun actionBasedTerminate(prio: Int, maxActions: Int) = object : AExplorationStrategy() {
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean =
			eContext.explorationTrace.size >= maxActions

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			log.debug("Maximum number of actions reached. Terminate")
			return ExplorationAction.terminateApp()
		}
	}

	/**
	 * Restarts the exploration when the current state is an "app not responding" dialog
	 */
	fun resetOnAppCrash(prio: Int) = object: AExplorationStrategy() {
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean =
			eContext.getCurrentState().isAppHasStoppedDialogBox

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			log.debug("Current screen is 'App has stopped'. Reset")
			return waitForLaunch(eContext)
		}
		private var cnt = 0
		suspend fun waitForLaunch(explorationContext: ExplorationContext<*,*,*>): ExplorationAction{
			return when{
				cnt++ < 2 ->{
					//delay(maxWaitTime)
					val widgets = explorationContext.getCurrentState().widgets
					val closeButton = widgets.find { it.resourceId == "android:id/aerr_close" }
					if (closeButton != null) {
						closeButton.click()
					} else if (widgets.any { it.canInteractWith }){
						widgets.filter { it.canInteractWith }.random().click()
					}
					else {
						GlobalAction(ActionType.FetchGUI) // try to refetch after waiting for some time
					}
				}
				else -> explorationContext.resetApp()
			}
		}
	}

	/**
	 * Resets the exploration once a predetermined number of non-reset actions has been executed
	 */
	fun intervalReset(prio: Int, interval: Int) = object: AExplorationStrategy() {
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			val lastReset = eContext.explorationTrace.P_getActions()
				.indexOfLast { it.actionType == "ResetApp" }

			val currAction = eContext.explorationTrace.size
			val diff = currAction - lastReset

			return diff > interval
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			return eContext.resetApp()
		}
	}

	/**
	 * Randomly presses back.
	 *
	 * Expected bundle: [Probability (Double), java.util.Random].
	 *
	 * Passing a different bundle will crash the execution.
	 */
	fun randomBack(prio: Int, probability: Double, rnd: java.util.Random) = object : AExplorationStrategy(){
		override fun getPriority() = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			val value = rnd.nextDouble()

			val lastLaunchDistance = with(eContext.explorationTrace.getActions()) {
				size-lastIndexOf(findLast{ it.actionType.isLaunchApp() || it.actionType=="ResetApp" })
			}
			return (lastLaunchDistance > 3 && value < probability )
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			log.debug("Has triggered back probability and previous action was not to press back. Returning 'Back'")
			return ExplorationAction.closeAndReturn()
		}
	}

	fun handleNoProgress(prio: Int,  maxWaitTime: Long = 1000) = object : AExplorationStrategy() {
		private val stateFrequency = HashMap<State<Widget>,Int>()
		private val trace = Stack<State<Widget>>()
		private val queueLength = 10
		private val maxNoProgress = 10
		private val alpha = 0.2
		private val beta = 0.8
		private var currentNoProgress = 0
		override fun getPriority(): Int = prio
		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			if (trace.empty()) {
				trace.push(eContext.getCurrentState())
				return false
			}
			val prevState = trace.peek()
			val currentState = eContext.getCurrentState()
			if (prevState == currentState) {
				currentNoProgress += 1
			} else {
				trace.push(eContext.getCurrentState())
				currentNoProgress = 0
			}
			if (currentNoProgress > maxNoProgress)
				return true
			return false
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			currentNoProgress=0
			log.info("No progress. Reset app")
			return eContext.resetApp()
		}

	}
	fun handleUnstableState(prio: Int,maxWaitTime: Long = 500, maxTryFetchCnt: Int = 1) = object  : AExplorationStrategy(){
		override fun getPriority(): Int {
			return prio
		}

		var tryFetchCnt: Int = 0
		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			val lastAction = eContext.getLastAction()
			if (lastAction.prevState == eContext.model.emptyState.stateId)
				return false
			if (tryFetchCnt<maxTryFetchCnt) {
				if (lastAction.prevState == lastAction.resState )
					return true
				val prevState = eContext.model.getState(lastAction.prevState)
				val resState =  eContext.model.getState(lastAction.resState)
				if (prevState!=null && resState!=null && resState.widgets.size>0 ) {
					val widgets = resState.widgets
					val visibleWidgets = resState.widgets.filter { it.isVisible }
					val ratio = visibleWidgets.size*1.0/widgets.size
					if (ratio<0.5) {
						return true
					}
				}
			}
			tryFetchCnt = 0
			return false
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			tryFetchCnt++
			return GlobalAction(actionType = ActionType.FetchGUI)
		}
	}

	/**
	 * Check the current state for interactive UI elements to interact with,
	 * if none are available we try to
	 * 1. close keyboard & press back
	 *   (per default keyboard items would be interactive but the user may use a custom model where this is not the case)
	 * 2. reset the app (if the last action already was a press-back)
	 * 3. if there was a reset within the last 3 actions or the last action was a Fetch
	 *  - we try to wait for up to ${maxWaittime}s (default 5s) if any interactive element appears
	 *  - if the app has crashed we terminate
	 */
	fun handleTargetAbsence(prio: Int, maxWaitTime: Long = 500) = object : AExplorationStrategy(){
		private var cnt = 0
		private var pressbackCnt = 0
		private var waitCnt = 0
		private var clickScreen = false
		private var pressEnter = false
		// may be used to terminate if there are no targets after waiting for maxWaitTime
		private var terminate = false
		private var waitingForLaunch = false
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			val hasNext = !eContext.explorationCanMoveOn().also {
				if(it) {
					cnt = 0  // reset the counter if we can proceed
					pressbackCnt = 0
					clickScreen = false
					pressEnter = false
					terminate = false
					waitingForLaunch = false
					waitCnt = 0
				}
			}
			return hasNext
		}

		suspend fun waitForLaunch(eContext: ExplorationContext<*,*,*>): ExplorationAction{
			return when{
				cnt++ < 2 ->{
					delay(maxWaitTime)
					waitingForLaunch = true
					GlobalAction(ActionType.FetchGUI) // try to refetch after waiting for some time
				}
				terminate -> {
					log.debug("Cannot explore. Last action was reset. Previous action was to press back. Returning 'Terminate'")
					eContext.resetApp()
				}
				else -> eContext.resetApp()
			}
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			//DEBUG
			val currentState = eContext.getCurrentState()
			//END DEBUG
			val atuaMF: ATUAMF? = eContext.findWatcher { it is ATUAMF } as ATUAMF?
			if (atuaMF!=null) {
				atuaMF.registerNotProcessState(currentState)
			}
			val lastActionType = eContext.getLastActionType()
			val (lastLaunchDistance,secondLast) = with(
				eContext.explorationTrace.getActions().filterNot {
					it.actionType.isQueueStart()|| it.actionType.isQueueEnd() }
			){
				lastIndexOf(findLast{ it.actionType == "ResetApp" || it.actionType.isLaunchApp() }).let{ launchIdx ->
					val beforeLaunch = this.getOrNull(launchIdx - 1)
					Pair( size-launchIdx, beforeLaunch)
				}
			}
			val s = eContext.getCurrentState()
			val s_res = eContext.getState(eContext.getLastAction().resState)
			val s_prev = eContext.getState(eContext.getLastAction().prevState)
			return when {
				lastActionType.isLaunchApp() || lastActionType == "ResetApp" -> {
					log.debug("Cannot explore. Returning 'Wait'")
					waitForLaunch(eContext)
				}
				waitingForLaunch  -> {
					when {
						cnt<2 -> {
							log.debug("Cannot explore. Returning 'Wait'")
							waitForLaunch(eContext)
						}
						else -> {
							eContext.resetApp()
						}
					}
				}
				s == eContext.model.emptyState -> {
					eContext.resetApp()
				}
				s.isHomeScreen  -> {
					/*if (lastActionType.isPressBack()
						|| lastActionType=="PressHome" || lastActionType=="MinimizeMaximize" )*/
					if (lastActionType != "LaunchApp")
						eContext.launchApp()
					else
						eContext.resetApp()
				}
				lastActionType.isPressBack() -> {
					// if previous action was back, terminate
					if (s.isAppHasStoppedDialogBox) {
						log.debug("Cannot explore. Last action was back. Currently on an 'App has stopped' dialog. Returning 'Wait'")
						waitForLaunch(eContext)
					} else {
						//some screens require pressback 2 times to exit activity
						if (pressbackCnt < 2) {
							log.debug("Cannot explore. Try pressback again")
							pressbackCnt++
							ExplorationAction.pressBack()
						} else if (pressbackCnt < 3) {
							// Try double pressback
							pressbackCnt++
							log.debug("Cannot explore. Try double pressback")
							ActionQueue(arrayListOf(ExplorationAction.pressBack(), ExplorationAction.pressBack()), delay = 25)
						} else {
							log.debug("Cannot explore. Last action was back. Returning 'Launch'")
							eContext.launchApp()
						}
					}
				}
				// by default, if it cannot explore, presses back
				else -> {
					if ( !s.widgets.any { it.packageName == eContext.model.config.appName }) {
						if (lastActionType == "RotateUI"
							|| lastActionType == "MinimizeMaximize") {
							eContext.resetApp()
						} else {
							pressbackCnt += 1
							ExplorationAction.pressBack()
						}
					}
					else if (s.widgets.all { it.boundaries.equals(s.widgets.first().boundaries) })   {
						log.debug("Click on Screen")
						val largestWidget = s.widgets.maxByOrNull { it.boundaries.width+it.boundaries.height }
						if (largestWidget !=null && !clickScreen) {
							clickScreen = true
							largestWidget.click()
						} else {
							pressEnter = true
							ExplorationAction.pressEnter()
						}
					} else if (s.visibleTargets.isEmpty() && waitCnt <= 3 ) {
						delay(maxWaitTime)
						waitCnt++
						if (waitCnt < 2)
							GlobalAction(ActionType.FetchGUI)
						else
							GlobalAction(ActionType.MinimizeMaximize)
					}
					// if current state is not a relevent state
					 else if ( !s.visibleTargets.any { it.clickable }  ) {
						// for example: vlc video player
						log.debug("Cannot explore because of no actionable widgets. Randomly choose PressBack or Click")
						if (pressEnter || clickScreen) {
							pressbackCnt +=1
							log.debug("PressBack.")
							ExplorationAction.pressBack()
						} else
						{
							log.debug("Click on Screen")
							val largestWidget = s.widgets.maxByOrNull { it.boundaries.width+it.boundaries.height }
							if (largestWidget !=null) {
								clickScreen = true
								largestWidget.click()
							} else {
								pressEnter = true
								ExplorationAction.pressEnter()
							}
						}
					} else {
						pressbackCnt +=1
						ExplorationAction.pressBack()
					}
				}
			}
		}

	}

	/**
	 * Always clicks allow/ok for any runtime permission request
	 */
	fun allowPermission(prio: Int, maxTries: Int = 100) = object : AExplorationStrategy(){
		private var numPermissions = HashMap<UUID,Int>()  // avoid some options which are misinterpreted as permission request to be infinitely triggered
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean =
				numPermissions.compute(eContext.getCurrentState().uid){ _,v -> v?.inc()?: 0 } ?: 0 < maxTries
					&& eContext.getCurrentState().widgets.any { it.packageName.startsWith("com.google.android.") }
						&&  eContext.getCurrentState().isRequestRuntimePermissionDialogBox
						&& !eContext.getCurrentState().widgets.any { it.isKeyboard }

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			// we do not require the element with the text ALLOW or OK to be clickabe since there may be overlaying elements
			// which handle the touch event for this button, however as a consequence we may click non-interactive labels
			// that is why we restricted this strategy to be executed at most [maxTries] from the same state
			val allowButton: Widget? = eContext.getCurrentState().widgets.filter{it.isVisible}.let { widgets ->
				widgets.firstOrNull { it.resourceId == "com.android.packageinstaller:id/permission_allow_button" ||
				it.resourceId == "com.android.permissioncontroller:id/permission_allow_foreground_only_button" }
					?: widgets.firstOrNull { it.text.lowercase().contains("allow") } ?: widgets.firstOrNull { it.text.lowercase() == "ok" }
			}
			if (allowButton!=null)
				return allowButton.click(ignoreClickable = true)
			else
				return eContext.getCurrentState().widgets.filter { it.canInteractWith }.first().click()
		}
	}

	/**
	 * Random click on an system dialog to unblock the state. This strategy need to be put after allowPermission
	 */
	fun allowUncompatibleVersion(prio: Int) = object : AExplorationStrategy(){
		private var clickedButton = HashMap<UUID, Boolean>()
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean =
				eContext.getCurrentState().widgets.any { it.packageName=="android" }
						&& getClickableButton(eContext.getCurrentState().actionableWidgets)
						&& clickedButton.any{it.value == false}

		private fun getClickableButton(actionableWidgets: List<Widget>): Boolean {
			val invalideWidgets = ArrayList<UUID>()
			clickedButton.forEach {
				if (actionableWidgets.find { w -> w.uid == it.key } == null) {
					invalideWidgets.add(it.key)
				}
			}
			invalideWidgets.forEach {
				clickedButton.remove(it)
			}
			actionableWidgets.filter { it.clickable }.forEach { it ->
				if (!clickedButton.containsKey(it.uid)) {
					clickedButton.put(it.uid,false)
				}
			}
			if (clickedButton.isEmpty())
				return false
			return true
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			val actionWidgets = eContext.getCurrentState().actionableWidgets.filter { clickedButton.containsKey(it.uid) && clickedButton[it.uid] == false}
			val actionWidget = actionWidgets.random()
			clickedButton[actionWidget.uid] = true
			return actionWidget.click()
		}
	}

	fun denyPermission(prio: Int) = object : AExplorationStrategy(){
		override fun getPriority(): Int = prio
		var denyButton: Widget? = null

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			denyButton = eContext.getCurrentState().widgets.let { widgets ->
				widgets.find { it.resourceId == "com.android.packageinstaller:id/permission_deny_button" }
					?: widgets.find { it.text.toUpperCase() == "DENY" }
			}
			return denyButton != null
		}
		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction =
			denyButton?.click(ignoreClickable = true)
				?: throw IllegalStateException("Error In denyPermission strategy, strategy was executed but hasNext should be false")
	}

	/**
	 * Finishes the exploration once all widgets have been explored
	 * FIXME this strategy is insanely ineficient right now and should be avoided
	 */
	fun explorationExhausted(prio: Int) = object : AExplorationStrategy(){
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean =
			eContext.explorationTrace.size>2 && eContext.areAllWidgetsExplored()

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction =
			ExplorationAction.terminateApp()
	}

	/** press back if advertisment is detected */
	fun handleAdvertisment(prio: Int) = object : AExplorationStrategy() {
		override fun getPriority(): Int = prio

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean =
			eContext.getCurrentState().widgets.any { it.packageName == "com.android.vending" }

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> nextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction =
			ExplorationAction.pressBack()
	}

	fun manual(prio: Int) = object : AExplorationStrategy() {
		override fun getPriority(): Int {
			return prio
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
			return true
		}

		override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> computeNextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
			runBlocking {
				println("Do any action and then press anykey to continue")
				val line = readLine()
			}
			return GlobalAction(actionType = ActionType.FetchGUI)
		}

	}
}