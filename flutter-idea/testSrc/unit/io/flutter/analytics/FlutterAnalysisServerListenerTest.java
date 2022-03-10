/*
 * Copyright 2022 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.analytics;

import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.fixes.DartQuickFix;
import com.jetbrains.lang.dart.fixes.DartQuickFixSet;
import io.flutter.FlutterInitializer;
import io.flutter.testing.CodeInsightProjectFixture;
import io.flutter.testing.Testing;
import org.dartlang.analysis.server.protocol.AnalysisStatus;
import org.dartlang.analysis.server.protocol.PubStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Map;

import static io.flutter.analytics.FlutterAnalysisServerListener.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
    analytics.setTransport(transport);
    analytics.setCanSend(true);
    FlutterInitializer.setAnalytics(analytics);
    fasl = FlutterAnalysisServerListener.getInstance(project);
  }

  @After
  public void tearDown() {
    fasl.dispose();
  }

  @Test
  public void acceptedCompletion() throws Exception {
    Editor editor = editor();
    Testing.runOnDispatchThread(() -> {
      editor.getSelectionModel().setSelection(18, 18);
      fasl.lookupSelectionHandler = new FlutterAnalysisServerListener.LookupSelectionHandler();
      LookupImpl lookup = new LookupImpl(project, editor, new LookupArranger.DefaultArranger());
      LookupItem item = new LookupItem(LookupItem.TYPE_TEXT_ATTR, "gr");
      lookup.addItem(item, PrefixMatcher.ALWAYS_TRUE);
      lookup.addLookupListener(fasl.lookupSelectionHandler);
      LookupEvent lookupEvent = new LookupEvent(lookup, item, 'o');
      fasl.lookupSelectionHandler.itemSelected(lookupEvent);
    });
    assertEquals(1, transport.sentValues.size());
    Map<String, String> map = transport.sentValues.get(0);
    assertEquals("acceptedCompletion", map.get("ec"));
    assertEquals("gr", map.get("ea"));
    assertEquals("0", map.get("ev"));
  }

  @SuppressWarnings("ConstantConditions")
  @Test
  public void serverStatus() throws Exception {
    fasl.serverStatus(new AnalysisStatus(false, null), new PubStatus(false));
    assertEquals(4, transport.sentValues.size());
    checkStatus(transport.sentValues.get(0), ERRORS, "0");
    checkStatus(transport.sentValues.get(1), WARNINGS, "0");
    checkStatus(transport.sentValues.get(2), HINTS, "0");
    checkStatus(transport.sentValues.get(3), LINTS, "0");
  }

  private void checkStatus(@NotNull Map<String, String> map, String label, String value) {
    assertEquals("analysisServerStatus", map.get("ec"));
    assertEquals(label, map.get("ea"));
    assertEquals(value, map.get("ev"));
  }

  @Test
  public void quickFix() throws Exception {
    Editor editor = editor();
    DartAnalysisServerService analysisServer = DartAnalysisServerService.getInstance(project);
    PsiManager manager = PsiManager.getInstance(project);
    DartQuickFixSet quickFixSet = new DartQuickFixSet(manager, mainFile.getVirtualFile(), 18, null);
    DartQuickFix fix = new DartQuickFix(quickFixSet, 0);
    analysisServer.fireBeforeQuickFixInvoked(fix, editor, mainFile);
    assertEquals(1, transport.sentValues.size());
    Map<String, String> map = transport.sentValues.get(0);
    assertEquals(QUICK_FIX, map.get("ec"));
    assertEquals("", map.get("ea"));
    assertEquals("0", map.get("ev"));
  }

  @Test
  public void dasListenerTiming() throws Exception {
    fasl.requestListener.onRequest("{\"method\":\"test\",\"id\":\"2\"}");
    fasl.responseListener.onResponse("{\"event\":\"none\",\"id\":\"2\"}");
    assertEquals(1, transport.sentValues.size());
    Map<String, String> map = transport.sentValues.get(0);
    assertEquals(ROUND_TRIP_TIME, map.get("utc"));
    assertEquals("test", map.get("utv"));
    assertNotNull(map.get("utt")); // Not checking a computed value, duration.
  }

  @Test
  public void dasListenerLogging() throws Exception {
    fasl.requestListener.onRequest("{\"method\":\"test\",\"id\":\"2\"}");
    fasl.responseListener.onResponse("{\"event\":\"server.log\",\"params\":{\"entry\":{\"time\":\"1\",\"kind\":\"\",\"data\":\"\"}}}");
    assertEquals(1, transport.sentValues.size());
    Map<String, String> map = transport.sentValues.get(0);
    assertEquals(ANALYSIS_SERVER_LOG, map.get("ec"));
    assertEquals("time|1|kind||data|", map.get("ea"));
  }

  @Test
  public void dasListenerLoggingWithSdk() throws Exception {
    fasl.requestListener.onRequest("{\"method\":\"test\",\"id\":\"2\"}");
    fasl.responseListener.onResponse("{\"event\":\"server.log\",\"params\":{\"entry\":{\"time\":\"1\",\"kind\":\"\",\"data\":\"\",\"sdkVersion\":\"1\"}}}");
    assertEquals(1, transport.sentValues.size());
    Map<String, String> map = transport.sentValues.get(0);
    assertEquals(ANALYSIS_SERVER_LOG, map.get("ec"));
    assertEquals("time|1|kind||data|", map.get("ea"));
    assertEquals("1", map.get("cd2"));
  }

  @NotNull
  private Editor editor() throws Exception{
    //noinspection ConstantConditions
    Testing.runOnDispatchThread(() -> innerFixture.openFileInEditor(mainFile.getVirtualFile()));
    Editor e = innerFixture.getEditor();
    assert e != null;
    return e;
  }
}
