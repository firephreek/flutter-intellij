/*
 * Copyright 2022 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.analytics;

import com.intellij.codeInsight.lookup.LookupArranger;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupEvent;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import io.flutter.FlutterInitializer;
import io.flutter.testing.CodeInsightProjectFixture;
import io.flutter.testing.Testing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings({"LocalCanBeFinal"})
public class FlutterAnalysisServerListenerTest {
  private static final String fileContents = "void main() {\n" +
                                             "  group('group 1', () {\n" +
                                             "    test('test 1', () {});\n" +
                                             "  });\n" +
                                             "}";

  @Rule
  public final @NotNull CodeInsightProjectFixture projectFixture = Testing.makeCodeInsightModule();

  private @NotNull CodeInsightTestFixture innerFixture;
  private @NotNull Project project;
  private @NotNull PsiFile mainFile;
  private @NotNull MockAnalyticsTransport transport;
  private @NotNull Analytics analytics;
  private @NotNull FlutterAnalysisServerListener fasl;

  @SuppressWarnings("ConstantConditions")
  @Before
  public void setUp() {
    assert projectFixture.getInner() != null;
    innerFixture = projectFixture.getInner();
    assert innerFixture != null;
    project = innerFixture.getProject();
    assert project != null;
    mainFile = innerFixture.addFileToProject("lib/main.dart", fileContents);
    transport = new MockAnalyticsTransport();
    analytics = new Analytics("123e4567-e89b-12d3-a456-426655440000", "1.0", "IntelliJ CE", "2021.3.2");
    FlutterInitializer.setAnalytics(analytics);
    fasl = FlutterAnalysisServerListener.getInstance(project);
  }

  @After
  public void tearDown() {
    fasl.dispose();
  }

  @Test
  public void test() throws Exception {
    //noinspection ConstantConditions
    Testing.runOnDispatchThread(() -> innerFixture.openFileInEditor(mainFile.getVirtualFile()));
    Editor editor = innerFixture.getEditor();
    assert editor != null;
    Testing.runOnDispatchThread(() -> {
      editor.getSelectionModel().setSelection(18, 18);
      fasl.lookupSelectionHandler = new FlutterAnalysisServerListener.LookupSelectionHandler();
      LookupImpl lookup = new LookupImpl(project, editor, new LookupArranger.DefaultArranger());
      lookup.addLookupListener(fasl.lookupSelectionHandler);
      LookupEvent lookupEvent = new LookupEvent(lookup, false);
      fasl.lookupSelectionHandler.itemSelected(lookupEvent);
    });
  }
}
