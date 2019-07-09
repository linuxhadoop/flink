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

package org.apache.flink.api.common.functions;

import org.apache.flink.annotation.Public;

/**
 * The base interface for all user-defined functions.
 * 用户自定义函数的接口
 *
 * <p>This interface is empty in order to allow extending interfaces to
 * be SAM (single abstract method) interfaces that can be implemented via Java 8 lambdas.</p>
 *
 * 该接口是空的,为的是允许扩展成为SAM接口-这种接口可以通过jdk8的lambdas来实现
 *
 * ---------------------------------------------------------------------------------------------------
 * jdk8函数式接口，SAM类型的接口（Single Abstract Method）
 * 定义了这种类型的接口，使得以其为参数的方法，可以在调用时，使用一个lambda表达式作为参数
 * 从SAM原则上讲，这个接口中，只能有一个函数需要被实现，但是也可以有如下例外:
 *     1. 默认方法与静态方法并不影响函数式接口的契约，可以任意使用，即函数式接口中可以有静态方法，
 *     一个或者多个静态方法不会影响SAM接口成为函数式接口，并且静态方法可以提供方法实现可以由 default 修饰的默认方法方法，
 *     这个关键字是Java8中新增的，为的目的就是使得某一些接口，原则上只有一个方法被实现，但是由于历史原因，
 *     不得不加入一些方法来兼容整个JDK中的API，所以就需要使用default关键字来定义这样的方法
 *  2. 可以有 Object 中覆盖的方法，也就是 equals，toString，hashcode等方法。
 * ---------------------------------------------------------------------------------------------------
 */
@Public
public interface Function extends java.io.Serializable {
}
