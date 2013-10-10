/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.bootstrap;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.enterprise.inject.spi.ObserverMethod;
import javax.enterprise.inject.spi.ProcessAnnotatedType;

import org.jboss.weld.event.ExtensionObserverMethodImpl;
import org.jboss.weld.resources.spi.ClassFileInfo;
import org.jboss.weld.resources.spi.ClassFileServices;
import org.jboss.weld.util.Types;
import org.jboss.weld.util.reflection.Reflections;

import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class ExtensionObserverMethodResolver {

    private final ClassFileServices classFileInfoServices;
    private final Set<ExtensionObserverMethodImpl<?, ?>> objectObservers;
    private final Map<ExtensionObserverMethodImpl<?, ?>, Predicate<ClassFileInfo>> observers;

    private static class ExactTypePredicate implements Predicate<ClassFileInfo> {
        private final Class<?> type;

        public ExactTypePredicate(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean apply(ClassFileInfo input) {
            return type.getName().equals(input.getClassName());
        }
    }

    private static class UpperBoundPredicate implements Predicate<ClassFileInfo> {
        private final Class<?>[] bounds;

        public UpperBoundPredicate(Class<?>[] bounds) {
            this.bounds = bounds;
        }

        @Override
        public boolean apply(ClassFileInfo input) {
            for (Class<?> bound : bounds) {
                if (!input.isAssignableTo(bound)) {
                    return false;
                }
            }
            return true;
        }
    }

    public ExtensionObserverMethodResolver(ClassFileServices classFileInfoServices, Iterable<ObserverMethod<?>> observers) {
        this.classFileInfoServices = classFileInfoServices;
        this.objectObservers = Sets.newHashSet();
        this.observers = Maps.newHashMap();
        for (ObserverMethod<?> o : observers) {
            if (o instanceof ExtensionObserverMethodImpl<?, ?>) {
                ExtensionObserverMethodImpl<?, ?> observer = (ExtensionObserverMethodImpl<?, ?>) o;
                if (Object.class.equals(observer.getObservedType())) {
                    objectObservers.add(observer);
                }
                if (observer.getObservedType() instanceof ParameterizedType) {
                    ParameterizedType type = (ParameterizedType) observer.getObservedType();
                    if (ProcessAnnotatedType.class.equals(type.getRawType())) {
                        Type typeParameter = type.getActualTypeArguments()[0];
                        if (typeParameter instanceof Class<?> || typeParameter instanceof ParameterizedType) {
                            this.observers.put(observer, new ExactTypePredicate(Reflections.getRawType(typeParameter)));
                        } else if (typeParameter instanceof WildcardType) {
                            WildcardType wildCard = (WildcardType) typeParameter;
                            this.observers.put(observer, new UpperBoundPredicate(Types.getRawTypes(wildCard.getUpperBounds())));
                        } else if (typeParameter instanceof TypeVariable<?>) {
                            TypeVariable<?> variable = (TypeVariable<?>) typeParameter;
                            this.observers.put(observer, new UpperBoundPredicate(Types.getRawTypes(variable.getBounds())));
                        }
                    }
                }
                // TODO deal with Type variables properly
                // TODO deal with wildcards
                // TODO: ProcessAnnotatedType<? extends AlphaInterface<String>> - these need special handling
            }
        }
    }

    public Set<ExtensionObserverMethodImpl<?, ?>> resolveProcessAnnotatedTypeObservers(String className) {
        Set<ExtensionObserverMethodImpl<?, ?>> result = new HashSet<ExtensionObserverMethodImpl<?, ?>>();
        result.addAll(objectObservers);

        ClassFileInfo classInfo = classFileInfoServices.getClassFileInfo(className);
        for (Map.Entry<ExtensionObserverMethodImpl<?, ?>, Predicate<ClassFileInfo>> entry : observers.entrySet()) {
            ExtensionObserverMethodImpl<?, ?> observer = entry.getKey();
            if (containsRequiredAnnotation(classInfo, observer) && entry.getValue().apply(classInfo)) {
                result.add(observer);
            }
        }
        return result;
    }

    private boolean containsRequiredAnnotation(ClassFileInfo classInfo, ExtensionObserverMethodImpl<?, ?> observer) {
        if (observer.getRequiredAnnotations().isEmpty()) {
            return true;
        }
        for (Class<? extends Annotation> annotation : observer.getRequiredAnnotations()) {
            if (classInfo.containsAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }
}
