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

package org.calm.modelReuse

import org.atua.modelFeatures.ewtg.Input

class ModelHistoryInformation {
    fun getUsefulMoreOnceInput(inputList: List<Input>): List<Input> {
        val result = ArrayList<Input>()
        inputList.forEach {
            if (!inputUsefulness.containsKey(it))
                result.add(it)
            else {
                if (inputUsefulness[it]!!.second>0) {
                    if (inputUsefulness[it]!!.first==1)
                        result.add(it)
                    else if (inputUsefulness[it]!!.second>1) {
                        result.add(it)
                    }
                }
            }
        }
        return result
    }

    var inputUsefulness: HashMap<Input, Pair<Int,Int>> = HashMap() // totalCnt - incCnt
    var inputEffectiveness: HashMap<Input, Pair<Int,Int>> = HashMap()
    companion object{
        val INSTANCE: ModelHistoryInformation by lazy {
            ModelHistoryInformation()
        }
    }
}