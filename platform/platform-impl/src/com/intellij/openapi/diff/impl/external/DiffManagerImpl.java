/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.openapi.diff.impl.external;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.DiffManager;
import com.intellij.openapi.diff.DiffPanel;
import com.intellij.openapi.diff.DiffRequest;
import com.intellij.openapi.diff.DiffTool;
import com.intellij.openapi.diff.impl.ComparisonPolicy;
import com.intellij.openapi.diff.impl.DiffPanelImpl;
import com.intellij.openapi.diff.impl.DiffUtil;
import com.intellij.openapi.diff.impl.mergeTool.MergeTool;
import com.intellij.openapi.diff.impl.processing.HighlightMode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.MarkupEditorFilter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.util.config.*;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;

public class DiffManagerImpl extends DiffManager implements JDOMExternalizable {
  public static final int FULL_DIFF_DIVIDER_POLYGONS_OFFSET = 3;
  private static final Logger LOG = Logger.getInstance(DiffManagerImpl.class);

  private static final Externalizer<String> TOOL_PATH_UPDATE = new Externalizer<String>() {
    @NonNls private static final String NEW_VALUE = "newValue";

    @Override
    public String readValue(Element dataElement) {
      String path = dataElement.getAttributeValue(NEW_VALUE);
      if (path != null) return path;
      String prevValue = dataElement.getAttributeValue(VALUE_ATTRIBUTE);
      return prevValue != null ? prevValue.trim() : null;
    }

    @Override
    public void writeValue(Element dataElement, String path) {
      dataElement.setAttribute(VALUE_ATTRIBUTE, path);
      dataElement.setAttribute(NEW_VALUE, path);
    }
  };

  static final StringProperty FOLDERS_TOOL = new StringProperty("foldersTool", "");
  static final StringProperty FILES_TOOL = new StringProperty("filesTool", "");
  static final StringProperty MERGE_TOOL = new StringProperty("mergeTool", "");
  static final StringProperty MERGE_TOOL_PARAMETERS = new StringProperty("mergeToolParameters", "");
  static final BooleanProperty ENABLE_FOLDERS = new BooleanProperty("enableFolders", false);
  static final BooleanProperty ENABLE_FILES = new BooleanProperty("enableFiles", false);
  static final BooleanProperty ENABLE_MERGE = new BooleanProperty("enableMerge", false);


  private final ExternalizablePropertyContainer myProperties;
  private final ArrayList<DiffTool> myAdditionTools = new ArrayList<DiffTool>();
  public static final DiffTool INTERNAL_DIFF = new FrameDiffTool();

  public static final Key<Boolean> EDITOR_IS_DIFF_KEY = new Key<Boolean>("EDITOR_IS_DIFF_KEY");
  private static final MarkupEditorFilter DIFF_EDITOR_FILTER = new MarkupEditorFilter() {
    @Override
    public boolean avaliableIn(Editor editor) {
      return DiffUtil.isDiffEditor(editor);
    }
  };
  private ComparisonPolicy myComparisonPolicy;
  private HighlightMode myHighlightMode;

  @NonNls public static final String COMPARISON_POLICY_ATTR_NAME = "COMPARISON_POLICY";
  @NonNls public static final String HIGHLIGHT_MODE_ATTR_NAME = "HIGHLIGHT_MODE";

  public DiffManagerImpl() {
    myProperties = new ExternalizablePropertyContainer();
    myProperties.registerProperty(ENABLE_FOLDERS);
    myProperties.registerProperty(FOLDERS_TOOL, TOOL_PATH_UPDATE);
    myProperties.registerProperty(ENABLE_FILES);
    myProperties.registerProperty(FILES_TOOL, TOOL_PATH_UPDATE);
    myProperties.registerProperty(ENABLE_MERGE);
    myProperties.registerProperty(MERGE_TOOL, TOOL_PATH_UPDATE);
    myProperties.registerProperty(MERGE_TOOL_PARAMETERS);
  }

  @Override
  public DiffTool getIdeaDiffTool() { return INTERNAL_DIFF; }

  @Override
  public DiffTool getDiffTool() {
    DiffTool[] standardTools;
    // there is inner check in multiple tool for external viewers as well
    if (! ENABLE_FILES.value(myProperties) || ! ENABLE_FOLDERS.value(myProperties) || !ENABLE_MERGE.value(myProperties)) {
      DiffTool[] embeddableTools = {
        INTERNAL_DIFF,
        new MergeTool(),
        BinaryDiffTool.INSTANCE
      };
      standardTools = new DiffTool[]{
        ExtCompareFolders.INSTANCE,
        ExtCompareFiles.INSTANCE,
        ExtMergeFiles.INSTANCE,
        new MultiLevelDiffTool(Arrays.asList(embeddableTools)),
        INTERNAL_DIFF,
        new MergeTool(),
        BinaryDiffTool.INSTANCE
      };
    } else {
      standardTools = new DiffTool[]{
        ExtCompareFolders.INSTANCE,
        ExtCompareFiles.INSTANCE,
        ExtMergeFiles.INSTANCE,
        INTERNAL_DIFF,
        new MergeTool(),
        BinaryDiffTool.INSTANCE
      };
    }
    ArrayList<DiffTool> allTools = new ArrayList<DiffTool>(myAdditionTools);
    allTools.addAll(Arrays.asList(standardTools));
    return new CompositeDiffTool(allTools);
  }

  @Override
  public boolean registerDiffTool(@NotNull DiffTool tool) throws NullPointerException {
    if (myAdditionTools.contains(tool)) return false;
    myAdditionTools.add(tool);
    return true;
  }

  @Override
  public void unregisterDiffTool(DiffTool tool) {
    myAdditionTools.remove(tool);
    LOG.assertTrue(!myAdditionTools.contains(tool));
  }

  @Override
  public MarkupEditorFilter getDiffEditorFilter() {
    return DIFF_EDITOR_FILTER;
  }

  @Override
  public DiffPanel createDiffPanel(Window window, @NotNull Project project, DiffTool parentTool) {
    return new DiffPanelImpl(window, project, true, true, FULL_DIFF_DIVIDER_POLYGONS_OFFSET, parentTool);
  }

  @Override
  public DiffPanel createDiffPanel(Window window, @NotNull Project project, @NotNull Disposable parentDisposable, DiffTool parentTool) {
    DiffPanel diffPanel = createDiffPanel(window, project, parentTool);
    Disposer.register(parentDisposable, diffPanel);
    return diffPanel;
  }

  public static DiffManagerImpl getInstanceEx() {
    return (DiffManagerImpl)DiffManager.getInstance();
  }

  @Override
  public void readExternal(@NotNull Element element) throws InvalidDataException {
    myProperties.readExternal(element);
    readPolicy(element);
    readMode(element);
  }

  private void readPolicy(@NotNull final Element element) {
    final String policyName = element.getAttributeValue(COMPARISON_POLICY_ATTR_NAME);
    if (policyName != null) {
      ComparisonPolicy[] policies = ComparisonPolicy.getAllInstances();
      for (ComparisonPolicy policy : policies) {
        if (policy.getName().equals(policyName)) {
          myComparisonPolicy = policy;
          break;
        }
      }
    }
  }

  private void readMode(@NotNull final Element element) {
    final String modeName = element.getAttributeValue(HIGHLIGHT_MODE_ATTR_NAME);
    if (modeName != null) {
      try {
        myHighlightMode = HighlightMode.valueOf(modeName);
      }
      catch (IllegalArgumentException ignore) {
      }
    }
  }

  @Override
  public void writeExternal(@NotNull Element element) throws WriteExternalException {
    myProperties.writeExternal(element);
    if (myComparisonPolicy != null) {
      element.setAttribute(COMPARISON_POLICY_ATTR_NAME, myComparisonPolicy.getName());
    }
    if (myHighlightMode != null) {
      element.setAttribute(HIGHLIGHT_MODE_ATTR_NAME, myHighlightMode.name());
    }
  }

  public AbstractProperty.AbstractPropertyContainer getProperties() { return myProperties; }

  static DiffPanel createDiffPanel(DiffRequest data, Window window, @NotNull Disposable parentDisposable, FrameDiffTool tool) {
    DiffPanel diffPanel = null;
    try {
      diffPanel = DiffManager.getInstance().createDiffPanel(window, data.getProject(), parentDisposable, tool);
      int contentCount = data.getContents().length;
      LOG.assertTrue(contentCount == 2, String.valueOf(contentCount));
      LOG.assertTrue(data.getContentTitles().length == contentCount);
      diffPanel.setDiffRequest(data);
      return diffPanel;
    }
    catch (RuntimeException e) {
      if (diffPanel != null) {
        Disposer.dispose(diffPanel);
      }
      throw e;
    }
  }

  public void setComparisonPolicy(final ComparisonPolicy p) {
    myComparisonPolicy = p;
  }

  @Nullable
  public ComparisonPolicy getComparisonPolicy() {
    return myComparisonPolicy;
  }

  public void setHighlightMode(HighlightMode highlightMode) {
    myHighlightMode = highlightMode;
  }

  @Nullable
  public HighlightMode getHighlightMode() {
    return myHighlightMode;
  }
}
