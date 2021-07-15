package org.droidmate.exploration.modelFeatures.atua.DSTG

import org.droidmate.exploration.modelFeatures.atua.ATUAMF
import org.droidmate.exploration.modelFeatures.atua.modelReuse.ModelVersion
import org.droidmate.explorationModel.ConcreteId
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class AbstractTransition(
        val abstractAction: AbstractAction,
        val interactions: HashSet<Interaction<*>> = HashSet(),
        val isImplicit: Boolean/*,
        var prevWindow: Window?*/,
        var data: Any? =null,
        var fromWTG: Boolean = false,
        val source: AbstractState,
        val dest: AbstractState,
        val modelVersion: ModelVersion = ModelVersion.RUNNING
) {

    val guaranteedAVMs = ArrayList<AttributeValuationMap>() // guaranteedAVMsInDest
    val modifiedMethods = HashMap<String,Boolean>() //method id,
    val modifiedMethodStatement = HashMap<String, Boolean>() //statement id,
    val handlers = HashMap<String,Boolean>() // handler method id
    val tracing = HashSet<Pair<Int,Int>>() // list of traceId-transitionId
    val statementCoverage = HashSet<String>()
    val methodCoverage = HashSet<String>()
    val changeEffects = HashSet<ChangeEffect>()
    // ----------Guard
    val userInputs = ArrayList<HashMap<UUID,String>>()
    val inputGUIStates = ArrayList<ConcreteId>()
    var dependentAbstractStates = ArrayList<AbstractState>()
    var requiringPermissionRequestTransition: AbstractTransition? = null

    var guardEnabled: Boolean = false
    // --------------
    init {
        source.abstractTransitions.add(this)
        guaranteedAVMs.addAll(dest.attributeValuationMaps)
    }

    fun isExplicit() = !isImplicit

    fun updateUpdateStatementCoverage(statement: String, atuaMF: ATUAMF) {
        val methodId = atuaMF.statementMF!!.statementMethodInstrumentationMap.get(statement)
        if (atuaMF.statementMF!!.isModifiedMethodStatement(statement)) {
            this.modifiedMethodStatement.put(statement, true)
            if (methodId != null) {
                atuaMF.allModifiedMethod.put(methodId,true)
                this.modifiedMethods.put(methodId, true)
            }
        }
        statementCoverage.add(statement)
        methodCoverage.add(methodId!!)
        // update Handler
        if (atuaMF.allEventHandlers.contains(methodId) ) {
            if (handlers.containsKey(methodId)) {
                handlers[methodId] = true
            } else {
                handlers.put(methodId, true)
            }
        }
    }

    fun copyPotentialInfoFrom(other: AbstractTransition) {
        this.dependentAbstractStates.addAll(other.dependentAbstractStates)
        this.guardEnabled = guardEnabled
        this.userInputs.addAll(other.userInputs)
        this.tracing.addAll(other.tracing)
        this.handlers.putAll(other.handlers)
        this.modifiedMethods.putAll(other.modifiedMethods)
        this.modifiedMethodStatement.putAll(other.modifiedMethodStatement)
        this.methodCoverage.addAll(other.methodCoverage)
        this.statementCoverage.addAll(other.statementCoverage)
    }

    fun updateGuardEnableStatus() {
        val input = source.inputMappings.get(abstractAction)
        if (input != null) {
            if (AbstractStateManager.INSTANCE.guardedTransitions.contains(Pair(source.window, input.first()))) {
                guardEnabled = true
            }
        }
    }

    companion object{
        fun computeAbstractTransitionData(actionType: AbstractActionType, interaction: Interaction<Widget>, guiState: State<Widget>, abstractState: AbstractState, atuaMF: ATUAMF): Any? {
            if (actionType == AbstractActionType.RANDOM_KEYBOARD) {
                return interaction.targetWidget
            }
            if (actionType == AbstractActionType.TEXT_INSERT) {
                val avm = abstractState.getAttributeValuationSet(interaction.targetWidget!!,guiState,atuaMF)
                if (avm!=null) {
                    return interaction.data
                }
                return null
            }
            if (actionType == AbstractActionType.SEND_INTENT)
                return interaction.data
            if (actionType != AbstractActionType.SWIPE) {
                return null
            }
            return interaction.data
        }


        fun findExistingAbstractTransitions(abstractTransitionSet: List<AbstractTransition>,
                                                     abstractAction: AbstractAction,
                                                     source: AbstractState,
                                                     dest: AbstractState,
                                                    isImplicit: Boolean): AbstractTransition? {
            var existingAbstractTransition: AbstractTransition? = null
          /*  if (prevWindowAbstractState!=null)
                    existingAbstractTransition = abstractTransitionSet.find {
                        it.abstractAction == abstractAction
                                && it.isImplicit == isImplicit
                                && it.data == interactionData
                                && it.source == source
                                && it.dest == dest
                                && it.dependentAbstractStates.contains(prevWindowAbstractState)
                    }
            if (existingAbstractTransition!=null)
                return existingAbstractTransition
            existingAbstractTransition = abstractTransitionSet.find {
                it.abstractAction == abstractAction
                        && it.isImplicit == isImplicit
                        && it.data == interactionData
                        && it.source == source
                        && it.dest == dest
            }
            if (existingAbstractTransition!=null)
                return existingAbstractTransition*/
            existingAbstractTransition = abstractTransitionSet.find {
                it.abstractAction == abstractAction
                        && it.isImplicit == isImplicit
                        && it.source == source
                        && it.dest == dest
            }
            return existingAbstractTransition
        }
    }
}