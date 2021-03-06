/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.properties;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNPropertyValue;

/**
 * TODO: Add correct support of binary properties - support in api, diff, etc.
 *
 * @author Konstantin Kolosovsky.
 */
public class PropertyValue {

  @NotNull private final String myValue;

  @Nullable
  public static PropertyValue create(@Nullable SVNPropertyValue value) {
    return create(SVNPropertyValue.getPropertyAsString(value));
  }

  private PropertyValue(@NotNull String propertyValue) {
    myValue = propertyValue;
  }

  @Nullable
  public static PropertyValue create(@Nullable String propertyValue) {
    return propertyValue == null ? null : new PropertyValue(propertyValue);
  }

  @Nullable
  public static String toString(@Nullable PropertyValue value) {
    return value == null ? null : value.myValue;
  }

  public String toString() {
    return myValue;
  }
}
