package org.droidmate.exploration.modelFeatures.autaut.DSTG

import org.droidmate.exploration.modelFeatures.graph.*
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class AbstractTransitionGraph(private val graph: IGraph<AbstractState, AbstractTransition> =
                              Graph(AbstractStateManager.instance.appResetState,
                                      stateComparison = { a, b -> a == b },
                                      labelComparison = { a, b ->
                                        a==b
                                      })): IGraph<AbstractState, AbstractTransition> by graph{
    // val edgeConditions: HashMap<Edge<*,*>,HashMap<WidgetGroup,String>> = HashMap()
    val edgeConditions: HashMap<Edge<*,*>,ArrayList<HashMap<Widget,String>>> = HashMap()
    val edgeProved: HashMap<Edge<*,*>, Int> = HashMap()
    val statementCoverageInfo: HashMap<Edge<*,*>,ArrayList<String>> = HashMap()
    val methodCoverageInfo: HashMap<Edge<*,*>,ArrayList<String>> = HashMap()

    fun containsCondition(edge: Edge<*,*>, condition: HashMap<Widget,String>): Boolean {
        if (edgeConditions.get(edge)!!.contains(condition)) {
            return true
        }
        return false
    }

    fun addNewCondition(edge: Edge<*,*>, condition: HashMap<Widget,String>) {
        edgeConditions.get(edge)!!.add(condition)
    }

    fun getWindowBackward(abstractState: AbstractState): List<Edge<*, *>>{
        val pressBackEvents = arrayListOf<Edge<*, *>>()
        this.edges(abstractState).filter { it.label.abstractAction.actionType == AbstractActionType.PRESS_BACK }.forEach {
            pressBackEvents.add(it)
        }
        return pressBackEvents
    }

    override fun add(source: AbstractState, destination: AbstractState?, label: AbstractTransition, updateIfExists: Boolean, weight: Double): Edge<AbstractState, AbstractTransition> {
        val edge = graph.add(source, destination, label, updateIfExists, weight)
        edgeProved.put(edge,0)
        edgeConditions.put(edge, ArrayList())
        methodCoverageInfo.put(edge, ArrayList())
        statementCoverageInfo.put(edge,ArrayList())
        return edge
    }

    override fun update(source: AbstractState, prevDestination: AbstractState?, newDestination: AbstractState, prevLabel: AbstractTransition, newLabel: AbstractTransition): Edge<AbstractState, AbstractTransition>? {
        val edge = graph.update(source, prevDestination,newDestination, prevLabel, newLabel)
        if (edge!=null) {
            edgeProved.put(edge, 0)
            edgeConditions.put(edge, ArrayList())
            methodCoverageInfo.put(edge, ArrayList())
            statementCoverageInfo.put(edge, ArrayList())
        }
        return edge
    }
    
    fun dump(statementCoverageMF: StatementCoverageMF, bufferedWriter: BufferedWriter) {
        bufferedWriter.write(header())
        val fromResetState = AbstractStateManager.instance.launchAbstractStates.get(AbstractStateManager.LAUNCH_STATE.RESET_LAUNCH)!!
        val fromResetAbstractState = AbstractStateManager.instance.getAbstractState(fromResetState)!!
        val dumpedSourceStates = ArrayList<AbstractState>()
        recursiveDump(fromResetAbstractState,statementCoverageMF,dumpedSourceStates, bufferedWriter)
                
    }

    private fun header(): String {
        return "SourceState;ResultingState;ActionType;InteractedAVS;Data;PrevWindow;Handlers;CoveredUpdatedMethods;CoveredUpdatedStatements;GUITransitionID"
    }

    fun recursiveDump(sourceAbstractState: AbstractState, statementCoverageMF: StatementCoverageMF, dumpedSourceStates: ArrayList<AbstractState> , bufferedWriter: BufferedWriter) {
        dumpedSourceStates.add(sourceAbstractState)
        val explicitEdges = this.edges(sourceAbstractState).filter { it.label.isExplicit() && it.destination!=null }
        val nextSources = ArrayList<AbstractState>()
        explicitEdges.forEach { edge ->
            if (!nextSources.contains(edge.destination!!.data) && !dumpedSourceStates.contains(edge.destination!!.data)) {

                nextSources.add(edge.destination!!.data)
            }
            bufferedWriter.newLine()
            val abstractTransitionInfo = "${sourceAbstractState.abstractStateId};${edge.destination!!.data.abstractStateId};" +
                    "${edge.label.abstractAction.actionType};${edge.label.abstractAction.attributeValuationSet?.avsId};${edge.label.data};" +
                    "${edge.label.prevWindow};\"${getInteractionHandlers(edge,statementCoverageMF)}\";\"${getCoveredModifiedMethods(edge,statementCoverageMF)}\";\"${getCoveredUpdatedStatements(edge,statementCoverageMF)}\";" +
                    "\"${edge.label.interactions.map { it.actionId }.joinToString(separator = ";")}\""
            bufferedWriter.write(abstractTransitionInfo)

        }
        nextSources.forEach {
            recursiveDump(it,statementCoverageMF,dumpedSourceStates, bufferedWriter)
        }
    }

    private fun getCoveredModifiedMethods(edge: Edge<AbstractState, AbstractTransition>, statementCoverageMF: StatementCoverageMF): String {
        return edge.label.modifiedMethods.filterValues { it == true }.map { statementCoverageMF.getMethodName(it.key) }.joinToString(separator = ";")
    }

    private fun getCoveredUpdatedStatements(edge: Edge<AbstractState, AbstractTransition>, statementCoverageMF: StatementCoverageMF): String {
        return edge.label.modifiedMethodStatement.filterValues { it == true }.map { it.key }.joinToString(separator = ";")
    }

    private fun getInteractionHandlers(edge: Edge<AbstractState, AbstractTransition>, statementCoverageMF: StatementCoverageMF) =
            edge.label.handlers.filter { it.value == true }.map { it.key }.map { statementCoverageMF.getMethodName(it) }.joinToString(separator = ";")

    companion object {

        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(AbstractTransitionGraph::class.java) }


    }
}