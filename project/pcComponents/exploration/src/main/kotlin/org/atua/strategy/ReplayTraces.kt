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

package org.atua.strategy

import kotlinx.coroutines.runBlocking
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.calm.StringComparison
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.resetApp
import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.Click
import org.droidmate.deviceInterface.exploration.ClickEvent
import org.droidmate.deviceInterface.exploration.GlobalAction
import org.droidmate.deviceInterface.exploration.LongClick
import org.droidmate.deviceInterface.exploration.LongClickEvent
import org.droidmate.deviceInterface.exploration.Swipe
import org.droidmate.deviceInterface.exploration.TextInsert
import org.droidmate.deviceInterface.exploration.Tick
import org.droidmate.exploration.actions.click
import org.droidmate.exploration.actions.launchApp
import org.droidmate.exploration.actions.longClick
import org.droidmate.exploration.actions.rotate
import org.droidmate.exploration.actions.setText
import org.droidmate.exploration.actions.terminateApp
import org.droidmate.exploration.actions.tick
import org.droidmate.exploration.strategy.AExplorationStrategy
import org.droidmate.explorationModel.ConcreteId
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.factory.AbstractModel
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class ReplayTraces(
    val atuaMF: ATUAMF,
    val delay: Long,
    val useCoordinateClicks: Boolean
) : AExplorationStrategy()  {

    private var expectedStateId: ConcreteId? = null
    var delayCheckingBlockStates = 0
    var tryCount = 0
    var forceEnd = false
    var episodeCountDown = 0
    var traceId=0
    var transitionId=0
    val stopTrace: Int
    init {
        transitionId=0
        stopTrace = atuaMF.traceId
    }

    var recentTargetEvent: Input? = null
    val obsolescentStates = ArrayList<AbstractState>()

    override fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> initialize(initialContext: ExplorationContext<M, S, W>) {
        super.initialize(initialContext)
    }

    override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
        transitionId++
        return true
    }


    override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> computeNextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
        atuaMF.dstg.cleanPredictedAbstractStates()
        //TODO Update target windows
        val currentState = eContext.getCurrentState()
        val currentAppState = atuaMF.getAbstractState(currentState)
        if (currentAppState == null) {
            throw Exception("DSTG is not updated.")
        }
        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState?.window}")
        if (obsolescentStates.contains(currentAppState)) {
            obsolescentStates.remove(currentAppState)
        }
        if (expectedStateId != null && currentState.stateId != expectedStateId) {
            val expectedState = atuaMF.stateList.find { it.stateId == expectedStateId }!!
            val expectedAbstractState = atuaMF.getAbstractState(expectedState)
            if (expectedAbstractState != null)
                obsolescentStates.add(expectedAbstractState)
        }
        var chosenAction: ExplorationAction? = null
        ExplorationTrace.widgetTargets.clear()
        if (traceId > stopTrace) {
            log.info("No more sequences.")
            return ExplorationAction.terminateApp()
        }
        while (chosenAction == null) {
            val interactions = atuaMF.tracingInteractionsMap[Pair(traceId, transitionId)]
            if (interactions == null) {
                if (transitionId == 0) {
                    return eContext.resetApp()
                } else {
                    if (atuaMF.tracingInteractionsMap.any { it.key.first > traceId }) {
                        traceId++
                        transitionId = 0
                    } else {
                        log.info("No more sequences.")
                        return ExplorationAction.terminateApp()
                    }
                }
            } else {
                if (interactions.size == 1) {
                    val interaction = interactions.single()
                    log.info("Next action: ${interaction.actionType}")
                    val sourceState = atuaMF.stateList.find { it.stateId == interaction.prevState }!!
                    val sourceAppState = atuaMF.getAbstractState(sourceState)!!
                    if (sourceAppState.window == currentAppState.window) {
                        expectedStateId = interaction.resState
                        chosenAction =
                            interaction.toExplorationAction(currentState, atuaMF, eContext, delay, useCoordinateClicks)
                        if (chosenAction != null) {
                            return chosenAction
                        } else
                            transitionId++
                    } else {
                        // check if any later states having the same window as the current state
                        transitionId++
                        var foundNextAction = false
                        while (true) {
                            if (!atuaMF.tracingInteractionsMap.containsKey(Pair(traceId,transitionId))){
                                break
                            }
                            val tmp_interaction = atuaMF.tracingInteractionsMap[Pair(traceId, transitionId)]!!.first()
                            val tmp_srcState = atuaMF.stateList.find { it.stateId == tmp_interaction.prevState }!!
                            val tmp_srcAppState = atuaMF.getAbstractState(tmp_srcState)!!
                            if (tmp_srcAppState.window == currentAppState.window) {
                                foundNextAction = true
                                break
                            }
                            transitionId++
                        }
                        if (!foundNextAction) {
                            if (atuaMF.tracingInteractionsMap.any { it.key.first > traceId }) {
                                log.info("Could not continue the sequence. Switch to the next sequence.")
                                traceId++
                                transitionId = 0
                            } else {
                                log.info("No more sequences.")
                                return ExplorationAction.terminateApp()
                            }
                        }
                    }
                } else {
                    //TODO create ActionQueue
                }
            }
        }
        if (chosenAction == null) {
            log.info("No more sequences.")
            return ExplorationAction.terminateApp()
        }
        return chosenAction
    }




    var clickedOnKeyboard = false
    var needResetApp = false
    var actionCountSinceSelectTarget: Int = 0

    var numOfContinousTry = 0



    override fun getPriority(): Int {
        return 0
    }

    companion object {
        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(ReplayTraces::class.java) }

    }
}

private fun <W : Widget> Interaction<W>.toExplorationAction(currentState: State<*>, atuamf: ATUAMF ,eContext: ExplorationContext<*, *, *>,delay: Long,useCoordinateClicks: Boolean): ExplorationAction? {
    if (targetWidget == null) {
        return when (actionType) {
            "FetchGUI","PressEnter","PressHome","CloseKeyboard","PressBack","PressMenu","MinimizeMaximize" -> GlobalAction(actionType = ActionType.values().find { it.name.equals(actionType)}!!)
            "LaunchApp" -> eContext.launchApp()
            "ResetApp" -> eContext.resetApp()
            "RotateUI" -> {
                val rotation = runBlocking {
                    atuamf.getDeviceRotation()
                }
                if (rotation == 0 || rotation == 2){
                    ExplorationAction.rotate(90)
                } else {
                    ExplorationAction.rotate(-90)
                }
            }
            "Click" -> {
                val coordinator = Helper.parseCoordinationData(data)
                Click(coordinator.first,coordinator.second)
            }
            "LongClick" -> {
                val coordinator = Helper.parseCoordinationData(data)
                LongClick(coordinator.first,coordinator.second)
            }
            "Swipe" -> {
                val swipeCoordinator = Helper.parseSwipeData(data)
                Swipe(start = swipeCoordinator[0],end = swipeCoordinator[1])
            }
            else -> {
                throw Exception("Not implemented action. ACtion $actionType ")
            }
        }
    } else {
        val toTriggerWidget = if (currentState.widgets.contains(targetWidget!!)) {
            targetWidget!!
        } else {
            val sameUIDWidgets = currentState.widgets.filter { it.uid == targetWidget!!.uid }
            if (sameUIDWidgets.isEmpty()) {
                null
            } else if (sameUIDWidgets.size == 1) {
                sameUIDWidgets.single()
            } else {
                val acceptInputWidgets = sameUIDWidgets.filter {
                    when (this.actionType) {
                        Click.name, ClickEvent.name -> it.clickable
                        LongClick.name,LongClickEvent.name -> it.longClickable
                        Tick.name -> it.checked!=null
                        TextInsert.name -> it.isInputField
                        else -> true
                    }
                }
                if (acceptInputWidgets.isNotEmpty()) {
                    val widgetWithScores = acceptInputWidgets.associateWith { it.similarScore(targetWidget!!) }
                    widgetWithScores.maxByOrNull { it.value}!!.key
                } else {
                    null
                }
            }
        }
        if (toTriggerWidget!=null) {
            return when (actionType) {
                "Click" , "ClickEvent"-> toTriggerWidget.click(delay,useCoordinateClicks)
                "LongClick", "LongClickEvent" -> toTriggerWidget.longClick(delay,useCoordinateClicks)
                "Tick" -> toTriggerWidget.tick(delay,useCoordinateClicks)
                "Swipe" -> {
                    val swipeCoordinator = Helper.parseSwipeData(data)
                    ExplorationTrace.widgetTargets.add(toTriggerWidget)
                    Swipe(start = swipeCoordinator[0],end = swipeCoordinator[1])
                }
                "TextInsert" -> toTriggerWidget.setText(this.data,sendEnter = false)
                else -> {
                    throw Exception("Not implemented action.")
                }
            }
        } else {
            return null
        }

    }
}


 fun Widget.similarScore(targetWidget: Widget): Double {
    var propertyCnt = 0
    var totalScore = 0.0
    if (this.resourceId.equals(targetWidget.resourceId))
        totalScore+=1.0
    propertyCnt++
    val score1 = StringComparison.compareStringsLevenshtein(targetWidget.nlpText,this.nlpText)
    totalScore+=score1
    propertyCnt++
    val score2 = StringComparison.compareStringsXpathCommonLongestSubsequence(targetWidget.xpath,this.xpath)
    totalScore += score2
    propertyCnt++
    return totalScore/propertyCnt

}
