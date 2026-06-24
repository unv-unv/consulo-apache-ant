package consulo.apache.ant.execution;

import com.intellij.java.compiler.impl.OutputParser;
import com.intellij.java.compiler.impl.javaCompiler.FileObject;
import com.intellij.java.compiler.impl.javaCompiler.javac.JavacOutputParser;
import consulo.application.Application;
import consulo.build.ui.FilePosition;
import consulo.build.ui.event.MessageEvent;
import consulo.build.ui.progress.BuildProgress;
import consulo.build.ui.progress.BuildProgressDescriptor;
import consulo.compiler.CompileContext;
import consulo.compiler.CompilerMessageCategory;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.navigation.Navigatable;
import consulo.process.ProcessHandler;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jetbrains.buildServer.messages.serviceMessages.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author VISTALL
 * @since 2023-05-08
 */
public class OutputBuilder implements OutputWatcher, MessageProcessor {
    private static final String JAVAC = "javac";
    private static final String ECHO = "echo";

    private static final Logger LOG = Logger.getInstance(OutputBuilder.class);
    private final Project myProject;
    private final BuildProgress<BuildProgressDescriptor> myBuildProgress;
    private final ProcessHandler myProcessHandler;
    private boolean isStopped;
    private List<String> myJavacMessages;
    private boolean myIsEcho;
    private int myErrorsCount;

    private Map<String, BuildProgress<BuildProgressDescriptor>> myTargets = new ConcurrentHashMap<>();
    private Map<String, BuildProgress<BuildProgressDescriptor>> myTasks = new ConcurrentHashMap<>();

    private Deque<BuildProgress<BuildProgressDescriptor>> myQueue = new ConcurrentLinkedDeque<>();

    public OutputBuilder(Project project, ProcessHandler processHandler, BuildProgress<BuildProgressDescriptor> buildProgress) {
        myProject = project;
        myProcessHandler = processHandler;
        myBuildProgress = buildProgress;

        myQueue.add(myBuildProgress);
    }

    @Override
    public int getErrorsCount() {
        return myErrorsCount;
    }

    @Override
    public final void stopProcess() {
        myProcessHandler.destroyProcess();
    }

    @Override
    public boolean isTerminateInvoked() {
        return myProcessHandler.isProcessTerminating();
    }

    @Override
    public final boolean isStopped() {
        return isStopped;
    }

    @Override
    public final void setStopped(boolean stopped) {
        isStopped = stopped;
    }

    @Override
    public void onMessage(@Nullable ServiceMessage serviceMessage, @Nonnull String text) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(text);
        }

        BuildProgress<BuildProgressDescriptor> current = myQueue.getLast();

        if (serviceMessage == null) {
            current.output(text + "\n", true);
            return;
        }

        if (serviceMessage instanceof BuildStatus buildStatus) {
            boolean started = "buildStarted".equals(buildStatus.getStatus());

            if (started) {
                // we already started it
            }
            else if (myErrorsCount > 0) {
                myBuildProgress.fail();
            }
            else {
                myBuildProgress.finish();
            }
        }
        else if (serviceMessage instanceof ProgressStart progressStart) {
            BuildProgress<BuildProgressDescriptor> childProgress =
                current.startChildProgress(LocalizeValue.localizeTODO("target: " + progressStart.getArgument()));
            myTargets.put(progressStart.getArgument(), childProgress);
            myQueue.addLast(childProgress);
        }
        else if (serviceMessage instanceof TestStarted started) {
            String taskName = started.getTestName();
            BuildProgress<BuildProgressDescriptor> childProgress =
                current.startChildProgress(LocalizeValue.localizeTODO("task: " + taskName));
            myTasks.put(taskName, childProgress);
            myQueue.addLast(childProgress);

            if (JAVAC.equals(taskName)) {
                myJavacMessages = new ArrayList<>();
            }
        }
        else if (serviceMessage instanceof TestFinished testFinished) {
            String taskName = testFinished.getTestName();
            BuildProgress<BuildProgressDescriptor> childProgress = myTasks.remove(taskName);
            List<String> javacMessages = myJavacMessages;
            myJavacMessages = null;
            int currentErrors = processJavacMessages(javacMessages, myQueue.getLast(), myProject);
            myErrorsCount += currentErrors;
            myIsEcho = false;

            if (childProgress != null) {
                if (currentErrors > 0) {
                    childProgress.fail();
                }
                else {
                    childProgress.finish();
                }

                myQueue.remove(childProgress);
            }
        }
        else if (serviceMessage instanceof ProgressFinish progressFinish) {
            BuildProgress<BuildProgressDescriptor> childProgress = myTargets.remove(progressFinish.getArgument());
            List<String> javacMessages = myJavacMessages;
            myJavacMessages = null;
            int currentErrors = processJavacMessages(javacMessages, myQueue.getLast(), myProject);
            myErrorsCount += currentErrors;
            myIsEcho = false;

            if (childProgress != null) {
                if (currentErrors > 0) {
                    childProgress.fail();
                }
                else {
                    childProgress.finish();
                }

                myQueue.remove(childProgress);
            }
        }
        else if (serviceMessage instanceof Message messageObj) {
            String messageText = messageObj.getText();
            // org.apache.tools.ant.Project.MSG_ERR = 0
            boolean isError = "0".equals(messageObj.getStatus());
            boolean isWarn = "1".equals(messageObj.getStatus());
            boolean isInfo = "2".equals(messageObj.getStatus());

            if (isError || isWarn || isInfo) {
                if (myJavacMessages != null) {
                    myJavacMessages.add(messageText);
                }
                else {
                    myQueue.getLast().output(messageText + "\n", !isError);

                    if (isError) {
                        myErrorsCount++;
                    }
                }
            }
        }

//    if (AntLoggerConstants.TARGET == tagName) {
//      setProgressStatistics(AntBundle.message("target.tag.name.status.text", tagValue));
//    }
//    else if (AntLoggerConstants.TASK == tagName) {
//      setProgressText(AntBundle.message("executing.task.tag.value.status.text", tagValue));
//      if (JAVAC.equals(tagValue)) {
//        myJavacMessages = new ArrayList<String>();
//      }
//      else if (ECHO.equals(tagValue)) {
//        myIsEcho = true;
//      }
//    }
//
//    if (myJavacMessages != null && (AntLoggerConstants.MESSAGE == tagName || AntLoggerConstants.ERROR == tagName)) {
//      myJavacMessages.add(tagValue);
//      return;
//    }
//
//    if (AntLoggerConstants.MESSAGE == tagName) {
//      if (myIsEcho) {
//        myMessageView.outputMessage(tagValue, AntBuildMessageView.PRIORITY_VERBOSE);
//      }
//      else {
//        myMessageView.outputMessage(tagValue, priority);
//      }
//    }
//    else if (AntLoggerConstants.TARGET == tagName) {
//      myMessageView.startTarget(tagValue);
//    }
//    else if (AntLoggerConstants.TASK == tagName) {
//      myMessageView.startTask(tagValue);
//    }
//    else if (AntLoggerConstants.ERROR == tagName) {
//      myMessageView.outputError(tagValue, priority);
//    }
//    else if (AntLoggerConstants.EXCEPTION == tagName) {
//      String exceptionText = tagValue.replace(AntLoggerConstants.EXCEPTION_LINE_SEPARATOR, '\n');
//      myMessageView.outputException(exceptionText);
//    }
//    else if (AntLoggerConstants.BUILD == tagName) {
//      myMessageView.startBuild(myBuildName);
//    }
//    else if (AntLoggerConstants.TARGET_END == tagName || AntLoggerConstants.TASK_END == tagName) {
//      final List<String> javacMessages = myJavacMessages;
//      myJavacMessages = null;
//      processJavacMessages(javacMessages, myMessageView, myProject);
//      myIsEcho = false;
//      if (AntLoggerConstants.TARGET_END == tagName) {
//        myMessageView.finishTarget();
//      }
//      else {
//        myMessageView.finishTask();
//      }
//    }
    }

    private static int processJavacMessages(
        List<String> javacMessages,
        BuildProgress<BuildProgressDescriptor> buildProgress,
        Project project
    ) {
        if (javacMessages == null) {
            return 0;
        }

        OutputParser outputParser = new JavacOutputParser(project);

        AtomicInteger errorCount = new AtomicInteger();
        OutputParser.Callback callback = new OutputParser.Callback() {
            private int myIndex = -1;

            @Override
            @Nullable
            public String getCurrentLine() {
                if (javacMessages == null || myIndex >= javacMessages.size()) {
                    return null;
                }
                return javacMessages.get(myIndex);
            }

            @Override
            public String getNextLine() {
                int size = javacMessages.size();
                int next = Math.min(myIndex + 1, javacMessages.size());
                myIndex = next;
                if (next >= size) {
                    return null;
                }
                return javacMessages.get(next);
            }

            @Override
            public void pushBack(String line) {
                myIndex--;
            }

            @Override
            public CompileContext.MessageBuilder newMessage(CompilerMessageCategory category, LocalizeValue message) {
                MessageEvent.Kind kind = switch (category) {
                    case CompilerMessageCategory.ERROR -> MessageEvent.Kind.ERROR;
                    case CompilerMessageCategory.WARNING -> MessageEvent.Kind.WARNING;
                    case CompilerMessageCategory.INFORMATION -> MessageEvent.Kind.INFO;
                    case CompilerMessageCategory.STATISTICS -> MessageEvent.Kind.STATISTICS;
                };

                return new CompileContext.MessageBuilder() {
                    private VirtualFile myFile = null;
                    private int myRow = -1, myColumn = -1;

                    @Override
                    public CompileContext.MessageBuilder url(String url) {
                        myFile = VirtualFileManager.getInstance().findFileByUrl(url);
                        return this;
                    }

                    @Override
                    public CompileContext.MessageBuilder position(int row, int column) {
                        myRow = row;
                        myColumn = column;
                        return this;
                    }

                    @Override
                    public CompileContext.MessageBuilder navigatable(Navigatable navigatable) {
                        return this;
                    }

                    @Override
                    public void add() {
                        if (category == CompilerMessageCategory.ERROR) {
                            errorCount.incrementAndGet();
                        }

                        Application.get().runReadAction(() -> {
                            if (myFile != null) {
                                FilePosition position =
                                    new FilePosition(new File(Objects.requireNonNull(myFile.getCanonicalPath())), myRow - 1, myColumn);
                                buildProgress.fileMessage(message, message, kind, position);
                            }
                            else {
                                buildProgress.message(message, message, kind, null);
                            }
                        });
                    }
                };
            }

            @Override
            public void setProgressText(LocalizeValue text) {
            }

            @Override
            public void fileProcessed(String path) {
            }

            @Override
            public void fileGenerated(FileObject path) {
            }
        };
        try {
            while (true) {
                if (!outputParser.processMessageLine(callback)) {
                    break;
                }
            }
        }
        catch (Exception e) {
            //ignore
        }

        return errorCount.get();
    }
}
