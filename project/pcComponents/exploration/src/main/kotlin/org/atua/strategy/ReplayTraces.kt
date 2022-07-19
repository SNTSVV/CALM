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

import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.Rotation
import org.calm.ewtgdiff.EWTGDiff
import org.calm.modelReuse.ModelHistoryInformation
import org.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.Cardinality
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.Goal
import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.isLaunchApp
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.resetApp
import org.atua.strategy.task.ExerciseTargetComponentTask
import org.atua.strategy.task.GoToAnotherWindowTask
import org.atua.strategy.task.GoToTargetWindowTask
import org.atua.strategy.task.RandomExplorationTask
import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.Click
import org.droidmate.deviceInterface.exploration.GlobalAction
import org.droidmate.deviceInterface.exploration.LongClick
import org.droidmate.deviceInterface.exploration.Swipe
import org.droidmate.exploration.actions.click
import org.droidmate.exploration.actions.longClick
import org.droidmate.exploration.actions.rotate
import org.droidmate.exploration.actions.tick
import org.droidmate.exploration.strategy.AExplorationStrategy
import org.droidmate.exploration.strategy.widget.RandomWidget
import org.droidmate.explorationModel.ConcreteId
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.factory.AbstractModel
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ReachabilityTestStrategy2(
    val atuaMF: ATUAMF,
    val delay: Long,
    val useCoordinateClicks: Boolean
) : AExplorationStrategy()  {

    private var expectedState: ConcreteId? = null
    var delayCheckingBlockStates = 0
    var tryCount = 0
    var forceEnd = false
    var episodeCountDown = 0
    var traceId=0
    var transitionId=0
    init {
        transitionId=1
    }

    var recentTargetEvent: Input? = null
    val obsolescentStates = ArrayList<AbstractState>()

    override fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> initialize(initialContext: ExplorationContext<M, S, W>) {
        super.initialize(initialContext)
    }

    override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> hasNext(eContext: ExplorationContext<M, S, W>): Boolean {
        return super.hasNext(eContext)
    }


    override suspend fun <M : AbstractModel<S, W>, S : State<W>, W : Widget> computeNextAction(eContext: ExplorationContext<M, S, W>): ExplorationAction {
        atuaMF.dstg.cleanPredictedAbstractStates()
        //TODO Update target windows
        val currentState = eContext.getCurrentState()
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState.window}")
        var chosenAction: ExplorationAction?
        ExplorationTrace.widgetTargets.clear()
        transitionId++
        val interactions = atuaMF.tracingInteractionsMap[Pair(traceId,transitionId)]
        if (interactions == null){
            chosenAction = eContext.resetApp()
        } else{
            if (interactions.size==1) {
                val interaction = interactions.single()
                expectedState = interaction.resState
                chosenAction = interaction.toExplorationAction(currentState,currentAppState,atuaMF,eContext, delay, useCoordinateClicks)
                if (chosenAction!=null) {
                    return chosenAction
                } else {
                    traceId++
                    transitionId = 1
                    return eContext.resetApp()
                }
            } else {
                traceId++
                transitionId = 1
                return eContext.resetApp()
            }
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
        private val log: Logger by lazy { LoggerFactory.getLogger(ReachabilityTestStrategy2::class.java) }

    }
}

private fun <W : Widget> Interaction<W>.toExplorationAction(currentState: State<*>, currentAppState: AbstractState, atuamf: ATUAMF ,eContext: ExplorationContext<*, *, *>,delay: Long,useCoordinateClicks: Boolean): ExplorationAction? {
    if (targetWidget == null) {
        return when (actionType) {
            "FetchGUI","PressEnter","LaunchApp","PressHome","CloseKeyboard","PressBack","PressMenu" -> GlobalAction(actionType = ActionType.valueOf(value = actionType))
            "RotateUI" -> {
                if (currentAppState.rotation == Rotation.PORTRAIT) {
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
                throw Exception("Not implemented action")
            }
        }
    } else {
        if (currentState.widgets.contains(targetWidget!!)) {
            return when (actionType) {
                "Click" -> targetWidget!!.click(delay,useCoordinateClicks)
                "LongClick" -> targetWidget!!.longClick(delay,useCoordinateClicks)
                "Tick" -> targetWidget!!.tick(delay,useCoordinateClicks)
                "Swipe" -> {
                    val swipeCoordinator = Helper.parseSwipeData(data)
                    ExplorationTrace.widgetTargets.add(targetWidget!!)
                    Swipe(start = swipeCoordinator[0],end = swipeCoordinator[1])
                }
                else -> {
                    throw Exception("Not implemented action.")
                }
            }
        } else {
            return null
        }
    }
}
