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

package org.calm.simmetrics

import com.google.common.base.Preconditions
import org.simmetrics.ListDistance
import org.simmetrics.ListMetric

class LevenshteinList<T> @JvmOverloads constructor(insertDelete: Float = 1.0f, substitute: Float = 1.0f) :
    ListMetric<T>, ListDistance<T> {
    private val maxCost: Float
    private val insertDelete: Float
    private val substitute: Float

    override fun toString(): String {
        return "Levenshtein [insertDelete=" + insertDelete + ", substitute=" + substitute + "]"
    }

    init {
        Preconditions.checkArgument(insertDelete > 0.0f)
        Preconditions.checkArgument(substitute >= 0.0f)
        maxCost = Math.max(insertDelete, substitute)
        this.insertDelete = insertDelete
        this.substitute = substitute
    }

    override fun compare(p0: MutableList<T>?, p1: MutableList<T>?): Float {
        return if (p0!!.isEmpty() && p1!!.isEmpty()) 1.0f else 1.0f - distance(p0, p1) / (maxCost * Math.max(
            p0!!.size,
            p1!!.size
        ).toFloat())
    }

    override fun distance(p0: MutableList<T>?, p1: MutableList<T>?): Float {
        return if (p0!!.isEmpty()) {
            p1!!.size.toFloat()
        } else if (p0.isEmpty()) {
            p0!!.size.toFloat()
        } else if (p0.hashCode() == p1.hashCode()) {
            0.0f
        } else {
            val tLength = p1!!.size
            val sLength = p0!!.size
            var v0 = FloatArray(tLength + 1)
            var v1 = FloatArray(tLength + 1)
            var i: Int
            i = 0
            while (i < v0.size) {
                v0[i] = i.toFloat() * insertDelete
                ++i
            }
            i = 0
            while (i < sLength) {
                v1[0] = (i + 1).toFloat() * insertDelete
                for (j in 0 until tLength) {
                    v1[j + 1] = min(
                        v1[j] + insertDelete,
                        v0[j + 1] + insertDelete,
                        v0[j] + if (p0[i] == p1[j]) 0.0f else substitute
                    )
                }
                val swap = v0
                v0 = v1
                v1 = swap
                ++i
            }
            v0[tLength]
        }
    }

    fun min(a: Float, b: Float, c: Float): Float {
        return Math.min(Math.min(a, b), c)
    }
}