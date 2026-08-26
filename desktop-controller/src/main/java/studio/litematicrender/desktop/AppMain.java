package studio.litematicrender.desktop;

import java.awt.EventQueue;
import java.awt.Font;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

public final class AppMain {
  private static FileChannel lockChannel;
  private static FileLock lock;

  private AppMain() {
  }

  public static void main(String[] args) {
    if (args.length > 0 && "--self-test".equals(args[0])) {
      System.exit(SelfTest.run());
      return;
    }
    final boolean webMode = args.length > 0 && "--web".equals(args[0]);
    AppPaths paths = null;
    AppLog log = null;
    try {
      paths = AppPaths.create();
      log = new AppLog(AppLog.createSessionFile(paths.logs));
      log.info("DsLR 1.0.0 统一实体坐标帧渲染器启动。运行 Java=" + System.getProperty("java.version") + "，java.home=" + System.getProperty("java.home"));
      if (!acquireLock(paths.runtime.resolve("controller.lock"))) {
        JOptionPane.showMessageDialog(null, "DsLR 已经在运行。请先查看任务栏或关闭现有窗口。", "DsLR", JOptionPane.INFORMATION_MESSAGE);
        return;
      }
      final AppPaths finalPaths = paths;
      final AppLog finalLog = log;
      Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
        public void uncaughtException(Thread thread, Throwable error) {
          finalLog.error("未捕获异常，线程=" + thread.getName(), error);
          showFatal(finalLog, error);
        }
      });
      configureLookAndFeel();
      final JavaRuntimeLocator.RuntimeInfo runtime = JavaRuntimeLocator.findBest(25, log);
      final MinecraftLaunchService launcher = new MinecraftLaunchService(paths, log);
      Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
        public void run() {
          launcher.close();
          releaseLock();
        }
      }, "lrs-shutdown"));
      if (webMode) {
        WebUiServer web = new WebUiServer(paths, log, launcher, runtime);
        web.start();
        web.await();
        releaseLock();
        return;
      }
      EventQueue.invokeLater(new Runnable() {
        public void run() {
          ControllerFrame frame = new ControllerFrame(finalPaths, finalLog, launcher, runtime);
          frame.setVisible(true);
        }
      });
    } catch (Throwable error) {
      if (log != null) log.error("控制器启动失败", error);
      showFatal(log, error);
      releaseLock();
    }
  }

  private static boolean acquireLock(Path path) throws Exception {
    lockChannel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      lock = lockChannel.tryLock();
      return lock != null;
    } catch (Exception error) {
      lockChannel.close();
      lockChannel = null;
      return false;
    }
  }

  private static void releaseLock() {
    try {
      if (lock != null) lock.release();
    } catch (Exception ignored) {
    }
    try {
      if (lockChannel != null) lockChannel.close();
    } catch (Exception ignored) {
    }
    lock = null;
    lockChannel = null;
  }

  private static void configureLookAndFeel() {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception ignored) {
    }
    FontUIResource font = new FontUIResource(new Font("SimHei", Font.PLAIN, 13));
    Enumeration<Object> keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      Object value = UIManager.get(key);
      if (value instanceof FontUIResource) UIManager.put(key, font);
    }
  }

  private static void showFatal(final AppLog log, Throwable error) {
    StringWriter stack = new StringWriter();
    error.printStackTrace(new PrintWriter(stack));
    String message = "DsLR 启动失败：\n" + (error.getMessage() == null ? error.toString() : error.getMessage());
    if (log != null) message += "\n\n日志：" + log.file();
    final String finalMessage = message;
    try {
      if (SwingUtilities.isEventDispatchThread()) JOptionPane.showMessageDialog(null, finalMessage, "DsLR 启动失败", JOptionPane.ERROR_MESSAGE);
      else SwingUtilities.invokeLater(new Runnable() {
        public void run() { JOptionPane.showMessageDialog(null, finalMessage, "DsLR 启动失败", JOptionPane.ERROR_MESSAGE); }
      });
    } catch (Throwable ignored) {
      System.err.println(finalMessage);
      System.err.println(stack.toString());
    }
  }
}
