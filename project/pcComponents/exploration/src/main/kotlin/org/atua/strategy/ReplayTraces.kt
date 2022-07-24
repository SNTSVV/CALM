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
import org.atua.modelFeatures.VerifyTracesMF
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
    val verifyTracesMF: VerifyTracesMF,
    val atuaMF: ATUAMF,
    val delay: Long,
    val useCoordinateClicks: Boolean
) : AExplorationStrategy()  {

    lateinit var eContext: ExplorationContext<*,*,*>
    private var prevState: State<Widget>? = null
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


    override fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> initialize(initialContext: ExplorationContext<M, S, W>) {
        super.initialize(initialContext)
        eContext = initialContext
    }

    override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {

        return true
    }

    var tryFetch = false
    val missingActions = ArrayList<Interaction<*>>()
    var lastExecutedInteraction: Interaction<*>? = null
    override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> computeNextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
        atuaMF.dstg.cleanPredictedAbstractStates()
        //TODO Update target windows
        val currentState = eContext.getCurrentState()
        if (currentState == eContext.model.emptyState) {
            if (lastExecutedInteraction == null)
                lastExecutedInteraction = eContext.getLastAction()
            return GlobalAction(ActionType.MinimizeMaximize)
        }
        if (expectedStateId != null && currentState.stateId != expectedStateId) {
            if (!tryFetch) {
                if (lastExecutedInteraction == null)
                    lastExecutedInteraction = eContext.getLastAction()
                tryFetch = true
                return GlobalAction(ActionType.FetchGUI)
            }
        }
        if (currentState == prevState) {
            if (!tryFetch) {
                if (lastExecutedInteraction == null)
                    lastExecutedInteraction = eContext.getLastAction()
                tryFetch = true
                return GlobalAction(ActionType.FetchGUI)
            }
        }
        val currentAppState = atuaMF.getAbstractState(currentState)
        if (currentAppState == null) {
            throw Exception("DSTG is not updated.")
        }
        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState?.window}")
       /* if (verifyTracesMF.obsolescentStates.contains(currentAppState)) {
            verifyTracesMF.obsolescentStates.remove(currentAppState)
        }*/

        if (expectedStateId != null && currentState.stateId != expectedStateId) {
            val expectedState = atuaMF.stateList.find { it.stateId == expectedStateId }!!
            val expectedAbstractState = atuaMF.getAbstractState(expectedState)!!
            if (expectedAbstractState != currentAppState) {
                if (!expectedAbstractState.ignored) {
                    val lastReplayedInteraction =
                        atuaMF.tracingInteractionsMap.entries.find { it.key.first == traceId && it.key.second == transitionId }?.value?.first()
                    if (lastReplayedInteraction != null) {
                        if (lastExecutedInteraction == null) {
                            log.info("Last executed interaction is null.")
                        }
                        verifyTracesMF.obsolescentStates.put(
                            expectedAbstractState,
                            Triple(lastReplayedInteraction, lastExecutedInteraction, missingActions.toList())
                        )
                        verifyTracesMF.computeDifferenceBetweenAbstractStates(currentAppState, expectedAbstractState)
                    }
                }

            }
        }
        transitionId++
        var chosenAction: ExplorationAction? = null
        ExplorationTrace.widgetTargets.clear()

        while (chosenAction == null) {
            if (traceId > stopTrace) {
                log.info("No more sequences.")
                return ExplorationAction.terminateApp()
            }
            val interactions = atuaMF.tracingInteractionsMap.entries.find { it.key.first == traceId && it.key.second == transitionId }?.value
            if (interactions == null) {
                if (transitionId == 0) {
                    val nextInteractions = atuaMF.tracingInteractionsMap.entries.find { it.key.first == traceId && it.key.second == transitionId+1}!!.value
                    expectedStateId = nextInteractions.first().prevState
                    prevState = null
                    lastExecutedInteraction = null
                    chosenAction =  eContext.resetApp()
                    break
                } else {
                    if (atuaMF.tracingInteractionsMap.any { it.key.first > traceId }) {
                        traceId++
                        transitionId = 0
                        missingActions.clear()
                    } else {
                        log.info("No more sequences.")
                        return ExplorationAction.terminateApp()
                    }
                }
            } else {
                if (interactions.size == 1) {
                    val interaction = interactions.single()
                    val sourceState = atuaMF.stateList.find { it.stateId == interaction.prevState }!!
                    val sourceAppState = atuaMF.getAbstractState(sourceState)!!
                    if (sourceAppState.window == currentAppState.window) {
                        chosenAction =
                            interaction.toExplorationAction(currentState, atuaMF, eContext, delay, useCoordinateClicks)
                        if (chosenAction != null) {
                            expectedStateId = interaction.resState
                            prevState = currentState
                            break
                        }
                    }
                    // check if any later states having the same window as the current state
                    transitionId++
                    var foundNextAction = false

                    while (true) {
                        if (!atuaMF.tracingInteractionsMap.keys.any{it.first == traceId && it.second == transitionId }){
                            break
                        }
                        val tmp_interaction = atuaMF.tracingInteractionsMap.entries.find { it.key.first == traceId && it.key.second == transitionId }!!.value.first()
                        val tmp_srcState = atuaMF.stateList.find { it.stateId == tmp_interaction.prevState }!!
                        val tmp_srcAppState = atuaMF.getAbstractState(tmp_srcState)!!
                        if (tmp_srcAppState.window == currentAppState.window) {
                            foundNextAction = true
                            break
                        }
                        if (tmp_interaction.actionType != "FetchGUI") {
                            missingActions.add(tmp_interaction)
                        } else {
                            log.info("Bypass FetchGUI action")
                        }
                        transitionId++
                    }
                    if (!foundNextAction) {
                        if (atuaMF.tracingInteractionsMap.any { it.key.first > traceId }) {
                            log.info("Could not continue the sequence. Switch to the next sequence.")
                            traceId++
                            transitionId = 0
                            missingActions.clear()
                        } else {
                            log.info("No more sequences.")
                            return ExplorationAction.terminateApp()
                        }
                    } else {
                        continue
                    }

                } else {
                    throw Exception("ActionQueue replay has not been implemented")
                }
            }
        }
        if (chosenAction == null) {
            log.info("No more sequences.")
            return ExplorationAction.terminateApp()
        } else {
            tryFetch = false
            lastExecutedInteraction = null
            log.info("Trace Id: $traceId - Transition Id: $transitionId")
            log.info("Action: ${chosenAction.name}")
            if (ExplorationTrace.widgetTargets.isNotEmpty()) {
                log.info("Widget: ${ExplorationTrace.widgetTargets.first}")
            }
            log.info("Expected stateId: $expectedStateId")
            val expectedState = atuaMF.stateList.find { it.stateId == expectedStateId }!!
            val expectedAppState = atuaMF.getAbstractState(expectedState)!!
            log.info("Expected AppState: $expectedAppState")
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

private fun doClickOutbound(currentState: State<*>): ExplorationAction? {
    val guiDimension = Helper.computeGuiTreeDimension(currentState)
    if (guiDimension.leftX - 50 < 0) {
        return Click(guiDimension.leftX, y = guiDimension.topY - 50)
    }
    if (guiDimension.topY - 50 < 0)
        return Click(guiDimension.leftX - 50, y = guiDimension.topY)
    return Click(guiDimension.leftX - 50, y = guiDimension.topY - 50)
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
                if (data.isBlank()) {
                    doClickOutbound(currentState)
                } else {
                    val coordinator = Helper.parseCoordinationData(data)
                    Click(coordinator.first, coordinator.second)
                }
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
