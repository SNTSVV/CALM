package org.droidmate.exploration.modelFeatures.atua.ewtg.window

import org.droidmate.exploration.modelFeatures.atua.ewtg.WindowManager

class Activity(classType: String,
               nodeId: String = getNodeId(),
               runtimeCreated: Boolean,
               baseModel: Boolean): Window(classType,nodeId,runtimeCreated, baseModel){
    override fun copyToRunningModel(): Window {
        val newActivity = getOrCreateNode(
                nodeId = getNodeId(),
                classType = this.classType,
                runtimeCreated = this.isRuntimeCreated,
                isBaseMode = false
        )
        return newActivity
    }

    override fun getWindowType(): String {
        return "Activity"
    }

    init {
        counter++
    }

    override fun isStatic(): Boolean {
        return true
    }
    override fun toString(): String {
        return "[Window][Activity]${super.toString()}"
    }
    companion object{
        var counter = 0
        fun getNodeId(): String = "Activity-${counter+1}"
        fun getOrCreateNode(nodeId: String, classType: String, runtimeCreated: Boolean, isBaseMode:Boolean): Activity {
            val node = if (isBaseMode) {
                WindowManager.instance.baseModelWindows.find { it.windowId == nodeId
                        && it is Activity}
            } else {
                WindowManager.instance.updatedModelWindows.find { it.windowId == nodeId
                        && it is Activity}
            }
            if (node != null)
                return node!! as Activity
            else
                return Activity(nodeId = nodeId, classType = classType, runtimeCreated = runtimeCreated,baseModel = isBaseMode)
        }
    }
}