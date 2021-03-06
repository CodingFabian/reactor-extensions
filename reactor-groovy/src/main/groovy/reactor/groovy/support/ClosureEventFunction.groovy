/*
 * Copyright (c) 2011-2016 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package reactor.groovy.support

import groovy.transform.CompileStatic
import reactor.bus.Event
import reactor.fn.Function

/**
 * @author Stephane Maldini
 */
@CompileStatic
class ClosureEventFunction<K,V> implements Function<Event<K>,V> {

	final Closure<V> callback
	final boolean eventArg = true

	ClosureEventFunction(Closure<V> cl) {
		callback = cl
		def argTypes = callback.parameterTypes
		eventArg = Event.isAssignableFrom(argTypes[0])
	}

	@Override
	V apply(Event<K> arg) {
		if (eventArg) {
			callback arg
		} else {
			callback arg.data
		}
	}
}
