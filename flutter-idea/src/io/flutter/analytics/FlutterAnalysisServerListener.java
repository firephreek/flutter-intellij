package io.flutter.analytics;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.dart.server.AnalysisServerListenerAdapter;
import com.google.dart.server.RequestListener;
import com.google.dart.server.ResponseListener;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.lookup.LookupEvent;
import com.intellij.codeInsight.lookup.LookupListener;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.fixes.DartQuickFix;
import com.jetbrains.lang.dart.fixes.DartQuickFixListener;
import java.beans.PropertyChangeEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.flutter.FlutterInitializer;
import io.flutter.utils.FileUtils;
import org.dartlang.analysis.server.protocol.AnalysisError;
import org.dartlang.analysis.server.protocol.AnalysisErrorType;
import org.dartlang.analysis.server.protocol.AnalysisStatus;
import org.dartlang.analysis.server.protocol.AvailableSuggestionSet;
import org.dartlang.analysis.server.protocol.ClosingLabel;
import org.dartlang.analysis.server.protocol.CompletionSuggestion;
import org.dartlang.analysis.server.protocol.HighlightRegion;
import org.dartlang.analysis.server.protocol.ImplementedClass;
import org.dartlang.analysis.server.protocol.ImplementedMember;
import org.dartlang.analysis.server.protocol.IncludedSuggestionRelevanceTag;
import org.dartlang.analysis.server.protocol.IncludedSuggestionSet;
import org.dartlang.analysis.server.protocol.NavigationRegion;
import org.dartlang.analysis.server.protocol.Occurrences;
import org.dartlang.analysis.server.protocol.Outline;
import org.dartlang.analysis.server.protocol.OverrideMember;
import org.dartlang.analysis.server.protocol.PubStatus;
import org.dartlang.analysis.server.protocol.RequestError;
import org.dartlang.analysis.server.protocol.SearchResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FlutterAnalysisServerListener extends AnalysisServerListenerAdapter
  implements Disposable {
  // statics
  static final String COMPUTED_ERROR = "computedError";
  static final String INITIAL_COMPUTE_ERRORS_TIME = "initialComputeErrorsTime";
  static final String INITIAL_HIGHLIGHTS_TIME = "initialHighlightsTime";
  static final String INITIAL_OUTLINE_TIME = "initialOutlineTime";
  static final String ROUND_TRIP_TIME = "roundTripTime";
  static final String QUICK_FIX = "quickFix";
  static final String UNKNOWN_LOOKUP_STRING = "<unknown>";
  static final String ANALYSIS_SERVER_LOG = "analysisServerLog";
  static final String ACCEPTED_COMPLETION = "acceptedCompletion";
  static final String REJECTED_COMPLETION = "rejectedCompletion";
  static final String E2E_IJ_COMPLETION_TIME = "e2eIJCompletionTime";
  static final String DURATION = "duration";
  static final String FAILURE = "failure";
  static final String SUCCESS = "success";
  static final String ERROR_TYPE_REQUEST = "R";
  static final String ERROR_TYPE_SERVER = "@";

  static final String DAS_STATUS_EVENT_TYPE = "analysisServerStatus";
  static final String IS_BLAZE_SYNC_SUCCESSFUL = "isBlazeSyncSuccessful";
  static final String IS_DART_ACTIVE_LANG = "isDartActiveLang";
  static final String IS_DART_ONLY_ACTIVE_LANG = "isDartOnlyActiveLang";
  static final String IS_DART_WORKSPACE_TYPE = "isDartWorkspaceType";
  static final String IS_DERIVE_TARGETS_ENABLED = "isDeriveTargetsEnabled";
  static final String[] ERROR_TYPES =
    new String[] {
      AnalysisErrorType.CHECKED_MODE_COMPILE_TIME_ERROR,
      AnalysisErrorType.COMPILE_TIME_ERROR,
      AnalysisErrorType.HINT,
      AnalysisErrorType.LINT,
      AnalysisErrorType.STATIC_TYPE_WARNING,
      AnalysisErrorType.STATIC_WARNING,
      AnalysisErrorType.SYNTACTIC_ERROR
    };

  static final String LOG_ENTRY_KIND = "kind";
  static final String LOG_ENTRY_TIME = "time";
  static final String LOG_ENTRY_DATA = "data";
  static final String LOG_ENTRY_SDK_VERSION = "sdkVersion";

  // variables to throttle certain, high frequency events
  private static final int COMPUTED_ERROR_SAMPLE_RATE = 100;
  private static final Duration INTERVAL_TO_REPORT_MEMORY_USAGE = Duration.ofHours(1);
  final RequestListener requestListener;
  final ResponseListener responseListener;
  final DartQuickFixListener quickFixListener;
  // instance members
  private final Project project;
  //private final LoggingService loggingService;
  private final Map<String, List<AnalysisError>> pathToErrors;
  private final Map<String, Instant> pathToErrorTimestamps;
  private final Map<String, Instant> pathToHighlightTimestamps;
  private final Map<String, Instant> pathToOutlineTimestamps;
  private final Map<String, RequestDetails> requestToDetails;
  FileEditorManagerListener fileEditorManagerListener;
  LookupSelectionHandler lookupSelectionHandler;
  private int computedErrorCounter = 0;
  private Instant nextMemoryUsageLoggedInstant = Instant.EPOCH;

  FlutterAnalysisServerListener(@NotNull Project project, DartAnalysisServerService analysisServer) {
    this.project = project;
    this.pathToErrors = new HashMap<>();
    this.pathToErrorTimestamps = new HashMap<>();
    this.pathToHighlightTimestamps = new HashMap<>();
    this.pathToOutlineTimestamps = new HashMap<>();
    this.requestToDetails = new HashMap<>();
    try {
      LookupManager.getInstance(project).addPropertyChangeListener(this::onPropertyChange);
    } catch (NullPointerException e) {
      // It's okay if we fail to register the listener during testing.
    }

    this.fileEditorManagerListener =
      new FileEditorManagerListener() {
        @Override
        public void fileOpened(
          @NotNull final FileEditorManager source, @NotNull final VirtualFile file) {
          // Record the time that this file was opened so that we'll be able to log
          // relative timings for errors, highlights, outlines, etc.
          String filePath = file.getPath();
          Instant nowInstant = Instant.now();
          pathToErrorTimestamps.put(filePath, nowInstant);
          pathToHighlightTimestamps.put(filePath, nowInstant);
          pathToOutlineTimestamps.put(filePath, nowInstant);
        }

        @Override
        public void selectionChanged(@NotNull FileEditorManagerEvent event) {}

        @Override
        public void fileClosed(@NotNull final FileEditorManager source, @NotNull final VirtualFile file) {
        }
      };
    project
      .getMessageBus()
      .connect()
      .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, fileEditorManagerListener);
    this.quickFixListener = new QuickFixListener();
    analysisServer.addQuickFixListener(this.quickFixListener);
    this.requestListener = new FlutterRequestListener();
    this.responseListener = new FlutterResponseListener();
    analysisServer.addRequestListener(this.requestListener);
    analysisServer.addResponseListener(this.responseListener);
    maybeSetServerLogSubscription(analysisServer);
  }

  @NotNull
  public static FlutterAnalysisServerListener getInstance(@NotNull final Project project) {
    return project.getService(FlutterAnalysisServerListener.class);
  }

  private static void maybeSetServerLogSubscription(DartAnalysisServerService analysisServer) {
    analysisServer.setServerLogSubscription(true);
  }

  @NotNull
  private static String safelyGetString(JsonObject jsonObject, String memberName) {
    if (jsonObject != null && StringUtil.isNotEmpty(memberName)) {
      JsonElement jsonElement = jsonObject.get(memberName);
      if (jsonElement != null) {
        return jsonElement.getAsString();
      }
    }
    return "";
  }

  @Override
  public void dispose() {
    // This is deprecated and marked for removal in 2021.3. If it is removed we will
    // have to do some funny stuff to support older versions of Android Studio.
    LookupManager.getInstance(project).removePropertyChangeListener(this::onPropertyChange);
  }

  @Override
  public void computedAnalyzedFiles(List<String> list) {}

  @Override
  public void computedAvailableSuggestions(@NotNull List<AvailableSuggestionSet> list, @NotNull int[] ints) {}

  @Override
  public void computedCompletion(
    String completionId,
    int replacementOffset,
    int replacementLength,
    List<CompletionSuggestion> completionSuggestions,
    List<IncludedSuggestionSet> includedSuggestionSets,
    List<String> includedElementKinds,
    List<IncludedSuggestionRelevanceTag> includedSuggestionRelevanceTags,
    boolean isLast,
    String libraryFilePathSD) {}

  @Override
  public void computedErrors(String path, List<AnalysisError> list) {
    list.forEach(this::logAnalysisError);
    pathToErrors.put(path, list);
    maybeLogInitialAnalysisTime(INITIAL_COMPUTE_ERRORS_TIME, path, pathToErrorTimestamps);
  }

  public List<AnalysisError> getAnalysisErrorsForFile(String path) {
    if (path == null) {
      return AnalysisError.EMPTY_LIST;
    }
    return pathToErrors.getOrDefault(path, AnalysisError.EMPTY_LIST);
  }

  /**
   * Iterate through all files in this {@link Project}, counting how many of each {@link
   * AnalysisErrorType} is in each file. The returned {@link HashMap} will contain the set of String
   * keys in ERROR_TYPES and values with the mentioned sums, converted to Strings.
   */
  @NotNull
  private HashMap<String, Integer> getTotalAnalysisErrorCounts() {
    // Create a zero-filled array of length ERROR_TYPES.length.
    int[] errorCountsArray = new int[ERROR_TYPES.length];

    // Iterate through each file in this project.
    for (String keyPath : pathToErrors.keySet()) {
      // Get the list of AnalysisErrors and remove any todos from the list, these are ignored in the
      // Dart Problems view, and can be ignored for any dashboard work.
      List<AnalysisError> errors = getAnalysisErrorsForFile(keyPath);
      errors.removeIf(e -> e.getType().equals(AnalysisErrorType.TODO));
      if (errors.isEmpty()) {
        continue;
      }

      // For this file, count how many of each ERROR_TYPES type we have and add this count to each
      // errorCountsArray[*]
      for (int i = 0; i < ERROR_TYPES.length; i++) {
        final int j = i;
        errorCountsArray[j] +=
          errors.stream().filter(e -> e.getType().equals(ERROR_TYPES[j])).count();
      }
    }

    // Finally, create and return the final HashMap.
    HashMap<String, Integer> errorCounts = new HashMap<>();
    for (int i = 0; i < ERROR_TYPES.length; i++) {
      errorCounts.put(ERROR_TYPES[i], errorCountsArray[i]);
    }
    return errorCounts;
  }

  @Override
  public void computedHighlights(String path, List<HighlightRegion> list) {
    maybeLogInitialAnalysisTime(INITIAL_HIGHLIGHTS_TIME, path, pathToHighlightTimestamps);
  }

  @Override
  public void computedImplemented(String s, List<ImplementedClass> list, List<ImplementedMember> list1) {}

  @Override
  public void computedLaunchData(String s, String s1, String[] strings) {}

  @Override
  public void computedNavigation(String s, List<NavigationRegion> list) {}

  @Override
  public void computedOccurrences(String s, List<Occurrences> list) {}

  @Override
  public void computedOutline(String path, Outline outline) {
    maybeLogInitialAnalysisTime(INITIAL_OUTLINE_TIME, path, pathToOutlineTimestamps);
  }

  @Override
  public void computedOverrides(String s, List<OverrideMember> list) {}

  @Override
  public void computedClosingLabels(String s, List<ClosingLabel> list) {}

  @Override
  public void computedSearchResults(String s, List<SearchResult> list, boolean b) {}

  @Override
  public void flushedResults(List<String> list) {}

  @Override
  public void requestError(RequestError requestError) {
    String code = requestError.getCode();
    if (code == null) {
      code = requestError.getMessage();
    }
    String stack = requestError.getStackTrace();
    String exception = composeException(ERROR_TYPE_REQUEST, code, stack);
    FlutterInitializer.getAnalytics().sendException(exception, false);
  }

  /**
   * Build an exception parameter containing type, code, and stack. Limit it to 150 chars.
   * @param type "R" for request error, "S" for server error
   * @param code error code or message
   * @param stack stack trace
   * @return exception description, value of "exd" parameter in analytics
   */
  private static String composeException(@NotNull String type, @Nullable String code, @Nullable String stack) {
    String exception = type + " ";
    if (code != null && !code.isEmpty()) {
      exception += code;
      if (stack != null && !stack.isEmpty()) {
        exception += "\n" + stack;
      }
    }
    else if (stack != null && !stack.isEmpty()) {
      exception += stack;
    }
    else {
      exception += "exception";
    }
    if (exception.length() > 150) {
      exception = exception.substring(0, 149);
    }
    return exception;
  }

  @Override
  public void serverConnected(String s) {}

  @Override
  public void serverError(boolean isFatal, String message, String stackTraceString) {
    String exception = composeException(ERROR_TYPE_SERVER, message, stackTraceString);
    FlutterInitializer.getAnalytics().sendException(exception, isFatal);
  }

  @Override
  public void serverIncompatibleVersion(String s) {}

  @Override
  public void serverStatus(AnalysisStatus analysisStatus, PubStatus pubStatus) {
    if (!analysisStatus.isAnalyzing()) {
      @NotNull HashMap<String, Integer> errorCounts = getTotalAnalysisErrorCounts();
      int errorCount = 0;
      errorCount += extractCount(errorCounts, AnalysisErrorType.CHECKED_MODE_COMPILE_TIME_ERROR);
      errorCount += extractCount(errorCounts, AnalysisErrorType.COMPILE_TIME_ERROR);
      errorCount += extractCount(errorCounts, AnalysisErrorType.SYNTACTIC_ERROR);
      FlutterInitializer.getAnalytics().sendEventMetric(DAS_STATUS_EVENT_TYPE, "ERRORS",  errorCount);
      int warningCount = 0;
      warningCount += extractCount(errorCounts, AnalysisErrorType.STATIC_TYPE_WARNING);
      warningCount += extractCount(errorCounts, AnalysisErrorType.STATIC_WARNING);
      FlutterInitializer.getAnalytics().sendEventMetric(DAS_STATUS_EVENT_TYPE, "WARNINGS",  warningCount);
      int hintCount = extractCount(errorCounts, AnalysisErrorType.HINT);
      FlutterInitializer.getAnalytics().sendEventMetric(DAS_STATUS_EVENT_TYPE, "HINTS",  hintCount);
      int lintCount = extractCount(errorCounts, AnalysisErrorType.LINT);
      FlutterInitializer.getAnalytics().sendEventMetric(DAS_STATUS_EVENT_TYPE, "LINTS",  lintCount);
    }
  }

  private static int extractCount(@NotNull Map<String, Integer> errorCounts, String name) {
    //noinspection Java8MapApi,ConstantConditions
    return errorCounts.containsKey(AnalysisErrorType.CHECKED_MODE_COMPILE_TIME_ERROR)
      ? errorCounts.get(AnalysisErrorType.CHECKED_MODE_COMPILE_TIME_ERROR)
      : 0;
  }

  @Override
  public void computedExistingImports(String file, Map<String, Map<String, Set<String>>> existingImports) {}

  private static void logCompletion(@NotNull String selection, int prefixLength, @NotNull String eventType) {
    FlutterInitializer.getAnalytics().sendEventMetric(eventType, selection, prefixLength);
  }

  void logE2ECompletionSuccessMS(long e2eCompletionMS) {
    FlutterInitializer.getAnalytics().sendTiming(E2E_IJ_COMPLETION_TIME, SUCCESS, e2eCompletionMS);
  }

  void logE2ECompletionErrorMS(long e2eCompletionMS) {
    FlutterInitializer.getAnalytics().sendTiming(E2E_IJ_COMPLETION_TIME, FAILURE, e2eCompletionMS);
  }

  private void logAnalysisError(@Nullable AnalysisError error) {
    if (error != null && (computedErrorCounter++ % COMPUTED_ERROR_SAMPLE_RATE) == 0) {
      FlutterInitializer.getAnalytics().sendEvent(COMPUTED_ERROR,  error.getCode(), "", Long.toString(Instant.now().toEpochMilli()));
    }
  }

  private void maybeLogInitialAnalysisTime(@NotNull String eventType, @NotNull String path, @NotNull Map<String, Instant> pathToStartTime) {
    if (!pathToStartTime.containsKey(path)) {
      return;
    }

    logFileAnalysisTime(eventType, path, Duration.between(pathToStartTime.get(path), Instant.now()).toMillis());
    pathToStartTime.remove(path);
  }

  private void logFileAnalysisTime(@NotNull String kind, String path, long analysisTime) {
    FlutterInitializer.getAnalytics().sendEvent(kind, DURATION, "", Long.toString(analysisTime));
  }

  /**
   * Observe when the active {@link LookupImpl} changes and register the {@link
   * LookupSelectionHandler} on any new instances.
   */
  void onPropertyChange(@NotNull PropertyChangeEvent propertyChangeEvent) {
    Object newValue = propertyChangeEvent.getNewValue();
    if (!(newValue instanceof LookupImpl)) {
      return;
    }

    this.lookupSelectionHandler = new LookupSelectionHandler();
    LookupImpl lookup = (LookupImpl) newValue;
    lookup.addLookupListener(lookupSelectionHandler);
  }

  static class LookupSelectionHandler implements LookupListener {
    @Override
    public void lookupCanceled(@NotNull LookupEvent event) {
      if (event.isCanceledExplicitly() && isDartLookupEvent(event)) {
        logCompletion(UNKNOWN_LOOKUP_STRING, -1, REJECTED_COMPLETION);
      }
    }

    @Override
    public void itemSelected(@NotNull LookupEvent event) {
      if (event.getItem() == null) {
        return;
      }
      String selection = event.getItem().getLookupString();
      LookupImpl lookup = (LookupImpl) event.getLookup();
      int prefixLength = lookup.getPrefixLength(event.getItem());

      if (isDartLookupEvent(event)) {
        logCompletion(selection, prefixLength, ACCEPTED_COMPLETION);
      }
    }

    @Override
    public void currentItemChanged(@NotNull LookupEvent event) {}

    private static boolean isDartLookupEvent(@NotNull LookupEvent event) {
      LookupImpl lookup = (LookupImpl) event.getLookup();
      return lookup != null && lookup.getPsiFile() != null
             && lookup.getPsiFile().getVirtualFile() != null
             && FileUtils.isDartFile(lookup.getPsiFile().getVirtualFile());
    }
  }

  private class QuickFixListener implements DartQuickFixListener {
    @Override
    public void beforeQuickFixInvoked(@NotNull DartQuickFix intention, @NotNull Editor editor, @NotNull PsiFile file) {
      String path = file.getVirtualFile().getPath();
      int lineNumber = editor.getCaretModel().getLogicalPosition().line + 1;
      List<String> errorsOnLine = pathToErrors.containsKey(path)
        ? pathToErrors.get(path).stream()
          .filter(error -> error.getLocation().getStartLine() == lineNumber)
          .map(error -> error.getCode())
          .collect(Collectors.toList())
        : ImmutableList.of();
      FlutterInitializer.getAnalytics().sendEventMetric(QUICK_FIX, intention.getText(), errorsOnLine.size());
    }
  }

  class FlutterRequestListener implements RequestListener {
    @Override
    public void onRequest(String jsonString) {
      JsonObject request = new Gson().fromJson(jsonString, JsonObject.class);
      RequestDetails details = new RequestDetails(request.get("method").getAsString(), Instant.now());
      String id = request.get("id").getAsString();
      requestToDetails.put(id, details);
    }
  }

  class FlutterResponseListener implements ResponseListener {
    @Override
    public void onResponse(String jsonString) {
      JsonObject response = new Gson().fromJson(jsonString, JsonObject.class);
      if (response == null) return;
      if (safelyGetString(response, "event").equals("server.log")) {
        JsonObject serverLogEntry = response.getAsJsonObject("params").getAsJsonObject("entry");
        if (serverLogEntry != null) {
          String sdkVersionValue = safelyGetString(serverLogEntry, LOG_ENTRY_SDK_VERSION);
          ImmutableMap<String, String> map;

          String logEntry = String.format("%s|%s|%s|%s|%s|%s",
                                          LOG_ENTRY_TIME,
                                          Integer.toString(serverLogEntry.get(LOG_ENTRY_TIME).getAsInt()),
                                          LOG_ENTRY_KIND,
                                          serverLogEntry.get(LOG_ENTRY_KIND).getAsString(),
                                          LOG_ENTRY_DATA,
                                          serverLogEntry.get(LOG_ENTRY_DATA).getAsString());
          // Log the "sdkVersion" only if it was provided in the event
          if (StringUtil.isEmpty(sdkVersionValue)) {
            FlutterInitializer.getAnalytics().sendEvent(ANALYSIS_SERVER_LOG, logEntry);
          }
          else {
            FlutterInitializer.getAnalytics().sendEventWithSdk(ANALYSIS_SERVER_LOG, logEntry, sdkVersionValue);
          }
        }
      }
      if (response.get("id") == null) {
        return;
      }
      String id = response.get("id").getAsString();
      RequestDetails details = requestToDetails.get(id);
      if (details != null) {
        FlutterInitializer.getAnalytics().sendTiming(ROUND_TRIP_TIME, details.method(),
          Duration.between(details.startTime(), Instant.now()).toMillis());
      }
      requestToDetails.remove(id);
    }
  }
}
