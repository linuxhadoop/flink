/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.PublicEvolving;

/**
 * Defines the chaining scheme for the operator. When an operator is chained to the
 * predecessor, it means that they run in the same thread. They become one operator
 * consisting of multiple steps.
 * 定义算子的chaining模式。
 * 当一个算子与前驱算子 链在一起时, 意味着他们可以运行在同一个线程中。
 * 它们变成了一个由多个步骤 组成的操作符
 *
 * <p>The default value used by the {@link StreamOperator} is {@link #HEAD}, which means that
 * the operator is not chained to its predecessor. Most operators override this with
 * {@link #ALWAYS}, meaning they will be chained to predecessors whenever possible.
 *
 * StreamOperator中的默认值为#HEAD, 意味着 算子不能与前一个算子 链在一起。
 * 多数算子会使用#ALWAYS进行覆盖, 意味着 只要有可能,就会跟前面的算子链在一起
 */
@PublicEvolving
public enum ChainingStrategy {

	/**
	 * Operators will be eagerly chained whenever possible.
	 * 只要有可能就会链在一起
	 *
	 * <p>To optimize performance, it is generally a good practice to allow maximal
	 * chaining and increase operator parallelism.
	 * 为了进行性能优化, 允许最大链 且 增加算子并行度
	 */
	ALWAYS,

	/**
	 * The operator will not be chained to the preceding or succeeding operators.
	 * 算子不与任何 其他算子进行链接 (前驱、后继 都不可以)
	 */
	NEVER,

	/**
	 * The operator will not be chained to the predecessor, but successors may chain to this
	 * operator.
	 * 算子不会与前驱算子进行链接, 但是后继节点可能会链接到该节点
	 */
	HEAD
}
