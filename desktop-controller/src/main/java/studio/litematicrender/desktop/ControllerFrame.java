package studio.litematicrender.desktop;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.JTextComponent;

final class ControllerFrame extends JFrame {
  private static final Color LIGHT_BG = new Color(240, 244, 250);
  private static final Color LIGHT_CARD = Color.WHITE;
  private static final Color LIGHT_INPUT = new Color(247, 249, 253);
  private static final Color LIGHT_TEXT = new Color(29, 36, 50);
  private static final Color LIGHT_MUTED = new Color(105, 116, 137);
  private static final Color DARK_BG = new Color(7, 16, 29);
  private static final Color DARK_CARD = new Color(12, 27, 45);
  private static final Color DARK_INPUT = new Color(16, 35, 57);
  private static final Color DARK_TEXT = new Color(228, 236, 248);
  private static final Color DARK_MUTED = new Color(148, 167, 194);
  private static final Color ACCENT = new Color(49, 94, 222);
  private static final Color READY = new Color(27, 139, 91);
  private static final Color ERROR = new Color(194, 65, 65);
  private static final Font FONT = new Font("SimHei", Font.PLAIN, 13);
  private static final Font FONT_SMALL = new Font("SimHei", Font.PLAIN, 11);
  private static final Font FONT_TITLE = new Font("SimHei", Font.BOLD, 17);

  private final AppPaths paths;
  private final AppLog log;
  private final MinecraftLaunchService launcher;
  private final JavaRuntimeLocator.RuntimeInfo runtime;
  private final JPanel root = new JPanel(new BorderLayout(0, 14));
  private JPanel columns;
  private final List<CardPanel> cards = new ArrayList<CardPanel>();
  private final JTextField instancePath = new JTextField();
  private final JComboBox<String> version = editableCombo();
  private final JComboBox<String> schematic = editableCombo();
  private final JComboBox<String> resourcePack = editableCombo();
  private final JRadioButton orthographic = new JRadioButton("正交对角视角", true);
  private final JRadioButton perspective = new JRadioButton("标准俯视（35.264°）");
  private final JSlider azimuthSlider = new JSlider(0, 359, 45);
  private final JSpinner azimuthValue = new JSpinner(new SpinnerNumberModel(45.0, 0.0, 359.999, 0.1));
  private final JSlider elevationSlider = new JSlider(0, 8999, 3526);
  private final JSpinner elevationValue = new JSpinner(new SpinnerNumberModel(35.264, 0.0, 89.99, 0.1));
  private final JCheckBox dir0 = new JCheckBox("+X +Z", true);
  private final JCheckBox dir90 = new JCheckBox("−X +Z", true);
  private final JCheckBox dir180 = new JCheckBox("−X −Z", true);
  private final JCheckBox dir270 = new JCheckBox("+X −Z", true);
  private final JComboBox<String> resolution = new JComboBox<String>(new String[] { "1080p", "2160p", "4320p", "自定义" });
  private final JTextField width = new JTextField("1920");
  private final JTextField height = new JTextField("1080");
  private final JComboBox<String> renderMode = new JComboBox<String>(new String[] { "单图渲染", "瓦片渲染" });
  private final JRadioButton transparent = new JRadioButton("透明底 PNG", true);
  private final JRadioButton white = new JRadioButton("白底 PNG");
  private final JComboBox<String> lighting = new JComboBox<String>(new String[] { "技术全亮（Gamma 拉满）", "原版标准光照", "模拟光照" });
  private final JRadioButton sequential = new JRadioButton("四向依次渲染", true);
  private final JRadioButton parallel = new JRadioButton("四向同时渲染（高风险）");
  private final JTextField outputPath = new JTextField();
  private final JTextField prefix = new JTextField("litematic");
  private final JTextArea status = new JTextArea(8, 28);
  private final JLabel instanceSummary = mutedLabel("等待读取 Minecraft 游戏文件夹。");
  private final JLabel schematicNotice = mutedLabel("选择实例内已扫描文件，或拖入、手动选择其他位置的 .litematic。");
  private final JLabel runtimeSummary = mutedLabel("");
  private final JCheckBox darkMode = new JCheckBox("夜间模式");
  private final JButton renderButton = button("开始 PNG 渲染", true);
  private final JButton probeButton = button("测试 Fabric 隐藏启动", false);
  private final JButton draftButton = button("导出任务 JSON（不启动 Minecraft）", false);
  private MinecraftInstance currentInstance;
  private WorkerSession activeSession;
  private Timer eventTimer;
  private boolean dark;
  private boolean rendering;

  ControllerFrame(AppPaths paths, AppLog log, MinecraftLaunchService launcher, JavaRuntimeLocator.RuntimeInfo runtime) {
    super("Desktop Litematic Render");
    this.paths = paths;
    this.log = log;
    this.launcher = launcher;
    this.runtime = runtime;
    build();
    bind();
    applyTheme(false);
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    setMinimumSize(new Dimension(720, 600));
    setSize(new Dimension(1500, 870));
    setLocationRelativeTo(null);
    addWindowListener(new WindowAdapter() {
      public void windowClosed(WindowEvent event) {
        if (eventTimer != null) eventTimer.stop();
        ControllerFrame.this.launcher.close();
      }
    });
    runtimeSummary.setText(runtime == null
      ? "未找到可用于 Fabric 的 Java 25；控制器仍可打开。"
      : "内部运行时：" + runtime.toString());
    outputPath.setText(Paths.get(System.getProperty("user.home"), "Pictures", "LitematicRenders").toString());
    addComponentListener(new ComponentAdapter() {
      public void componentResized(ComponentEvent event) { updateColumns(); }
    });
    updateColumns();
  }

  private void build() {
    for (JComponent selector : Arrays.<JComponent>asList(
      orthographic, perspective, dir0, dir90, dir180, dir270,
      transparent, white, sequential, parallel, darkMode, azimuthSlider, elevationSlider
    )) selector.setOpaque(false);
    root.setBorder(BorderFactory.createEmptyBorder(18, 28, 24, 28));
    root.add(header(), BorderLayout.NORTH);
    columns = new JPanel(new GridLayout(1, 3, 16, 0));
    columns.setOpaque(false);
    columns.add(leftColumn());
    columns.add(centerColumn());
    columns.add(rightColumn());
    JScrollPane scroll = new JScrollPane(columns, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setOpaque(false);
    scroll.getVerticalScrollBar().setUnitIncrement(18);
    root.add(scroll, BorderLayout.CENTER);
    setContentPane(root);
  }

  private JPanel header() {
    JPanel header = new JPanel(new BorderLayout());
    header.setOpaque(false);
    JPanel title = new JPanel();
    title.setOpaque(false);
    title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
    JLabel name = new JLabel("DsLR · PNG RENDERER");
    name.setFont(new Font("SimHei", Font.BOLD, 24));
    title.add(name);
    header.add(title, BorderLayout.WEST);
    JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
    tools.setOpaque(false);
    tools.add(runtimeSummary);
    tools.add(darkMode);
    header.add(tools, BorderLayout.EAST);
    return header;
  }

  private JPanel leftColumn() {
    JPanel column = column();
    CardPanel game = card("Minecraft 游戏文件夹");
    game.add(mutedLabel("通用：.minecraft    PCL 隔离：.minecraft\\versions\\版本文件夹"));
    game.add(row(instancePath, button("选择", false), new ActionListener() {
      public void actionPerformed(ActionEvent event) { chooseInstance(); }
    }));
    JButton read = button("读取游戏文件夹", true);
    read.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { inspectInstance(); }
    });
    game.add(read);
    game.add(label("目标 Minecraft 版本"));
    game.add(version);
    game.add(instanceSummary);

    CardPanel schematicCard = card("投影文件 / 原理图");
    schematicCard.add(schematicNotice);
    schematicCard.add(schematic);
    JButton chooseSchematic = button("选择 .litematic", false);
    chooseSchematic.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { chooseFile(schematic, false, ".litematic"); }
    });
    schematicCard.add(chooseSchematic);

    CardPanel packCard = card("材质包 / 资源包");
    resourcePack.addItem("Minecraft 默认材质包");
    resourcePack.setSelectedIndex(0);
    packCard.add(resourcePack);
    JButton choosePack = button("添加 ZIP 或资源包文件夹", false);
    choosePack.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { chooseFile(resourcePack, true, ".zip"); }
    });
    packCard.add(choosePack);
    packCard.add(mutedLabel("默认使用 Minecraft 原版材质；额外资源包不会复制进 PCL 实例。"));

    column.add(game);
    column.add(gap());
    column.add(schematicCard);
    column.add(gap());
    column.add(packCard);
    return column;
  }

  private JPanel centerColumn() {
    JPanel column = column();
    CardPanel camera = card("对角视角");
    ButtonGroup projections = new ButtonGroup();
    projections.add(orthographic);
    projections.add(perspective);
    camera.add(two(orthographic, perspective));
    camera.add(label("方位角（正轴夹角）"));
    camera.add(sliderRow(azimuthSlider, azimuthValue));
    camera.add(label("俯角"));
    camera.add(sliderRow(elevationSlider, elevationValue));
    camera.add(mutedLabel("Minecraft：Y 为上下轴，方位角在 X/Z 水平面旋转；标准俯角 35.264°。"));
    camera.add(label("相对四向"));
    camera.add(four(dir0, dir90, dir180, dir270));

    CardPanel task = card("渲染任务");
    task.add(mutedLabel("可直接输出 PNG；首次建议先用 1080p、单方向验证。"));
    task.add(renderButton);
    task.add(Box.createVerticalStrut(8));
    task.add(probeButton);
    task.add(Box.createVerticalStrut(8));
    task.add(draftButton);
    task.add(Box.createVerticalStrut(10));
    status.setEditable(false);
    status.setLineWrap(true);
    status.setWrapStyleWord(true);
    status.setText("渲染器已启动。现有 PCL Minecraft 进程不会被注入或关闭。\n日志：" + log.file());
    JScrollPane statusScroll = new JScrollPane(status);
    statusScroll.setBorder(new RoundedBorder(new Color(200, 208, 222), 9));
    task.add(statusScroll);

    column.add(camera);
    column.add(gap());
    column.add(task);
    return column;
  }

  private JPanel rightColumn() {
    JPanel column = column();
    CardPanel output = card("输出与执行");
    output.add(label("分辨率"));
    output.add(resolution);
    output.add(two(field("宽度", width), field("高度", height)));
    output.add(label("帧缓冲"));
    output.add(renderMode);
    ButtonGroup backgrounds = new ButtonGroup();
    backgrounds.add(transparent);
    backgrounds.add(white);
    output.add(two(transparent, white));
    output.add(label("光照"));
    output.add(lighting);
    ButtonGroup schedules = new ButtonGroup();
    schedules.add(sequential);
    schedules.add(parallel);
    output.add(sequential);
    output.add(parallel);
    output.add(mutedLabel("四向同时渲染会显著增加显存、内存与崩溃风险。"));
    output.add(label("PNG 输出文件夹"));
    JButton chooseOutput = button("选择", false);
    output.add(row(outputPath, chooseOutput, new ActionListener() {
      public void actionPerformed(ActionEvent event) { chooseOutput(); }
    }));
    output.add(label("文件名前缀"));
    output.add(prefix);
    output.add(Box.createVerticalStrut(12));
    JLabel state = mutedLabel("按方块面映射原版或资源包贴图；红石线、活塞头、半砖、楼梯等保留近似几何。 ");
    output.add(state);
    column.add(output);
    return column;
  }

  private void bind() {
    darkMode.addItemListener(new ItemListener() {
      public void itemStateChanged(ItemEvent event) { applyTheme(event.getStateChange() == ItemEvent.SELECTED); }
    });
    resolution.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) {
        String selected = String.valueOf(resolution.getSelectedItem());
        if ("1080p".equals(selected)) setResolution("1920", "1080");
        if ("2160p".equals(selected)) setResolution("3840", "2160");
        if ("4320p".equals(selected)) setResolution("7680", "4320");
      }
    });
    azimuthSlider.addChangeListener(new ChangeListener() {
      public void stateChanged(ChangeEvent event) { azimuthValue.setValue(Double.valueOf(azimuthSlider.getValue())); }
    });
    azimuthValue.addChangeListener(new ChangeListener() {
      public void stateChanged(ChangeEvent event) { azimuthSlider.setValue((int) Math.round(((Number) azimuthValue.getValue()).doubleValue())); }
    });
    elevationSlider.addChangeListener(new ChangeListener() {
      public void stateChanged(ChangeEvent event) { elevationValue.setValue(Double.valueOf(elevationSlider.getValue() / 100.0)); }
    });
    elevationValue.addChangeListener(new ChangeListener() {
      public void stateChanged(ChangeEvent event) { elevationSlider.setValue((int) Math.round(((Number) elevationValue.getValue()).doubleValue() * 100.0)); }
    });
    perspective.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) {
        elevationValue.setValue(Double.valueOf(35.264));
        elevationSlider.setValue(3526);
      }
    });
    probeButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { startProbe(); }
    });
    renderButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { startRender(); }
    });
    draftButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { saveDraft(); }
    });
    version.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) {
        if (currentInstance != null && version.isPopupVisible()) inspectInstance();
      }
    });
    schematic.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent event) { updateSchematicHint(); }
    });
    installDrop(instancePath, new FileConsumer() {
      public void accept(File file) { instancePath.setText(file.getAbsolutePath()); inspectInstance(); }
    });
    installDrop(editor(schematic), new FileConsumer() {
      public void accept(File file) { schematic.getEditor().setItem(file.getAbsolutePath()); updateSchematicHint(); }
    });
    installDrop(editor(resourcePack), new FileConsumer() {
      public void accept(File file) { resourcePack.getEditor().setItem(file.getAbsolutePath()); }
    });
    installDrop(outputPath, new FileConsumer() {
      public void accept(File file) { outputPath.setText(file.getAbsolutePath()); }
    });
  }

  private void inspectInstance() {
    final String rawPath = instancePath.getText().trim();
    final String preferred = comboText(version);
    if (rawPath.isEmpty()) {
      showError("请填写 Minecraft 游戏文件夹或版本隔离文件夹。");
      return;
    }
    setStatus("正在读取 Minecraft 游戏文件夹……", false);
    new SwingWorker<MinecraftInstance, Void>() {
      protected MinecraftInstance doInBackground() throws Exception {
        return MinecraftInstance.inspect(Paths.get(rawPath), preferred);
      }

      protected void done() {
        try {
          currentInstance = get();
          refill(version, currentInstance.versions, currentInstance.selectedVersion);
          ArrayList<String> schematicPaths = pathStrings(currentInstance.schematics);
          refill(schematic, schematicPaths, "");
          updateSchematicHint();
          ArrayList<String> packs = pathStrings(currentInstance.resourcePacks);
          packs.add(0, "Minecraft 默认材质包");
          refill(resourcePack, packs, "Minecraft 默认材质包");
          instanceSummary.setText(currentInstance.summary());
          String jsonState = currentInstance.hasSelectedVersionJson() ? "已找到版本 JSON" : "缺少同名版本 JSON";
          setStatus(currentInstance.summary() + "\n" + jsonState + "：" + currentInstance.versionJson, !currentInstance.hasSelectedVersionJson());
          log.info("读取 Minecraft 实例：" + currentInstance.root + "，版本=" + currentInstance.selectedVersion);
        } catch (Exception error) {
          log.error("读取 Minecraft 游戏文件夹失败", error);
          showError(cause(error));
        }
      }
    }.execute();
  }

  private void startProbe() {
    if (runtime == null || runtime.major < 25) {
      showError("没有找到 Java 25。控制器不会调用 PATH；请安装 Java 25 后重新打开程序。");
      return;
    }
    if (currentInstance == null) {
      showError("请先读取 Minecraft 游戏文件夹。");
      return;
    }
    final String selectedVersion = comboText(version);
    try {
      currentInstance = MinecraftInstance.inspect(currentInstance.root, selectedVersion);
    } catch (Exception error) {
      showError(error.getMessage());
      return;
    }
    if (!currentInstance.hasSelectedVersionJson()) {
      showError("目标版本缺少同名 JSON：" + currentInstance.versionJson);
      return;
    }
    rendering = false;
    probeButton.setEnabled(false);
    renderButton.setEnabled(false);
    setStatus("正在解析版本继承链、libraries、assets 与原生库……", false);
    launcher.close();
    new SwingWorker<MinecraftLaunchService.LaunchResult, Void>() {
      protected MinecraftLaunchService.LaunchResult doInBackground() throws Exception {
        activeSession = WorkerSession.createProbe(paths, currentInstance);
        return launcher.startWorker(currentInstance, activeSession, runtime);
      }

      protected void done() {
        try {
          MinecraftLaunchService.LaunchResult result = get();
          String pid = result.pid > 0 ? String.valueOf(result.pid) : "已启动";
          setStatus("隔离 Fabric Worker 已启动，PID：" + pid + "\n等待 Renderer ready 事件。\n启动日志：" + result.launchLog + "\nMinecraft 日志：" + result.minecraftLog, false);
          monitorEvents(result);
        } catch (Exception error) {
          probeButton.setEnabled(true);
          renderButton.setEnabled(true);
          log.error("Fabric 隐藏启动失败", error);
          showError(cause(error));
        }
      }
    }.execute();
  }

  private void startRender() {
    if (runtime == null || runtime.major < 25) {
      showError("没有找到 Java 25。控制器不会调用 PATH；请安装 Java 25 后重新打开程序。 ");
      return;
    }
    if (currentInstance == null) {
      showError("请先读取 Minecraft 游戏文件夹。 ");
      return;
    }
    final MinecraftInstance selectedInstance;
    final Map<String, Object> job;
    try {
      selectedInstance = MinecraftInstance.inspect(currentInstance.root, comboText(version));
      if (!selectedInstance.hasSelectedVersionJson()) throw new IllegalArgumentException("目标版本缺少同名 JSON：" + selectedInstance.versionJson);
      String litematic = comboText(schematic);
      if (litematic.isEmpty() || !Files.isRegularFile(Paths.get(litematic))) throw new IllegalArgumentException("请选择存在的 .litematic 文件。 ");
      String output = outputPath.getText().trim();
      if (output.isEmpty()) throw new IllegalArgumentException("请填写 PNG 输出文件夹。 ");
      job = buildJob(litematic, output, positiveInt(width.getText(), "输出宽度"), positiveInt(height.getText(), "输出高度"));
    } catch (Exception error) {
      showError(error.getMessage());
      return;
    }
    currentInstance = selectedInstance;
    rendering = true;
    renderButton.setEnabled(false);
    probeButton.setEnabled(false);
    draftButton.setEnabled(false);
    setStatus("正在准备 PNG 渲染：解析版本、复制 Worker 并启动隐藏 Minecraft…", false);
    launcher.close();
    new SwingWorker<MinecraftLaunchService.LaunchResult, Void>() {
      protected MinecraftLaunchService.LaunchResult doInBackground() throws Exception {
        activeSession = WorkerSession.createRender(paths, selectedInstance, job);
        return launcher.startWorker(selectedInstance, activeSession, runtime);
      }

      protected void done() {
        try {
          MinecraftLaunchService.LaunchResult result = get();
          String pid = result.pid > 0 ? String.valueOf(result.pid) : "已启动";
          setStatus("隔离 Fabric PNG Worker 已启动，PID：" + pid + "\n将读取 .litematic 并写出 PNG。\n会话：" + activeSession.directory + "\n启动日志：" + result.launchLog, false);
          monitorEvents(result);
        } catch (Exception error) {
          rendering = false;
          renderButton.setEnabled(true);
          probeButton.setEnabled(true);
          draftButton.setEnabled(true);
          log.error("PNG 渲染启动失败", error);
          showError(cause(error));
        }
      }
    }.execute();
  }

  private void monitorEvents(final MinecraftLaunchService.LaunchResult result) {
    if (eventTimer != null) eventTimer.stop();
    eventTimer = new Timer(1000, new ActionListener() {
      public void actionPerformed(ActionEvent event) {
        if (activeSession == null) return;
        Map<String, Object> last = activeSession.lastEvent();
        if (last == null) return;
        String type = String.valueOf(last.get("type"));
        String message = String.valueOf(last.get("message"));
        if ("ready".equals(type)) {
          if (!rendering) {
            eventTimer.stop();
            probeButton.setEnabled(true);
            renderButton.setEnabled(true);
            setStatus("Fabric PNG Renderer 已就绪。\n" + message + "\n运行时：" + result.runtime + "\n启动日志：" + result.launchLog, false);
          }
        } else if ("progress".equals(type) || "resources_reloaded".equals(type)) {
          String fraction = last.get("fraction") instanceof Number ? "（" + Math.round(((Number) last.get("fraction")).doubleValue() * 100d) + "%）" : "";
          setStatus(message + fraction + "\n会话：" + activeSession.directory + "\n启动日志：" + result.launchLog, false);
        } else if ("completed".equals(type)) {
          eventTimer.stop();
          rendering = false;
          renderButton.setEnabled(true);
          probeButton.setEnabled(true);
          draftButton.setEnabled(true);
          String outputs = outputLines(last.get("outputs"));
          setStatus(message + "\n\n输出：" + outputs + "\n\n会话日志：" + activeSession.directory, false);
          log.info("PNG 渲染完成：" + outputs);
        } else if ("failed".equals(type)) {
          eventTimer.stop();
          rendering = false;
          probeButton.setEnabled(true);
          renderButton.setEnabled(true);
          draftButton.setEnabled(true);
          setStatus("Fabric Worker 启动或渲染失败。\n" + message, true);
        }
      }
    });
    eventTimer.start();
  }

  private void saveDraft() {
    try {
      if (currentInstance == null) throw new IllegalArgumentException("请先读取 Minecraft 游戏文件夹。");
      String litematic = comboText(schematic);
      if (litematic.isEmpty() || !Files.isRegularFile(Paths.get(litematic))) throw new IllegalArgumentException("请选择存在的 .litematic 文件。");
      String output = outputPath.getText().trim();
      if (output.isEmpty()) throw new IllegalArgumentException("请填写 PNG 输出文件夹。");
      int outputWidth = positiveInt(width.getText(), "输出宽度");
      int outputHeight = positiveInt(height.getText(), "输出高度");
      Map<String, Object> job = buildJob(litematic, output, outputWidth, outputHeight);
      String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
      Path file = paths.drafts.resolve(stamp + "-render-job.json");
      Json.write(file, job);
      setStatus("渲染任务 JSON 已保存（不会启动 Minecraft）：\n" + file, false);
      log.info("导出渲染任务 JSON：" + file);
    } catch (Exception error) {
      showError(error.getMessage());
    }
  }

  private Map<String, Object> buildJob(String litematic, String output, int outputWidth, int outputHeight) {
    Map<String, Object> root = new LinkedHashMap<String, Object>();
    root.put("schemaVersion", Long.valueOf(1));
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("litematicPath", litematic);
    source.put("instancePath", currentInstance.root.toString());
    source.put("minecraftVersion", comboText(version));
    root.put("source", source);
    Map<String, Object> resources = new LinkedHashMap<String, Object>();
    String pack = comboText(resourcePack);
    ArrayList<Object> selectedPacks = new ArrayList<Object>();
    if (!pack.isEmpty() && !"Minecraft 默认材质包".equals(pack)) selectedPacks.add(pack);
    resources.put("selectedResourcePacks", selectedPacks);
    root.put("resources", resources);
    Map<String, Object> camera = new LinkedHashMap<String, Object>();
    camera.put("projection", orthographic.isSelected() ? "orthographic" : "perspective");
    camera.put("baseAzimuthDeg", azimuthValue.getValue());
    camera.put("elevationDeg", elevationValue.getValue());
    ArrayList<Object> offsets = new ArrayList<Object>();
    if (dir0.isSelected()) offsets.add(Long.valueOf(0));
    if (dir90.isSelected()) offsets.add(Long.valueOf(90));
    if (dir180.isSelected()) offsets.add(Long.valueOf(180));
    if (dir270.isSelected()) offsets.add(Long.valueOf(270));
    camera.put("selectedOffsetsDeg", offsets);
    root.put("camera", camera);
    Map<String, Object> outputObject = new LinkedHashMap<String, Object>();
    outputObject.put("directory", output);
    outputObject.put("width", Long.valueOf(outputWidth));
    outputObject.put("height", Long.valueOf(outputHeight));
    outputObject.put("background", transparent.isSelected() ? "transparent" : "white");
    outputObject.put("namingPrefix", prefix.getText().trim().isEmpty() ? "litematic" : prefix.getText().trim());
    root.put("output", outputObject);
    Map<String, Object> execution = new LinkedHashMap<String, Object>();
    execution.put("framebufferMode", renderMode.getSelectedIndex() == 0 ? "single" : "tiled");
    execution.put("directionSchedule", sequential.isSelected() ? "sequential" : "parallel");
    execution.put("lighting", lighting.getSelectedIndex() == 0 ? "technical_fullbright" : lighting.getSelectedIndex() == 1 ? "vanilla_standard" : "simulated");
    root.put("execution", execution);
    return root;
  }

  private String outputLines(Object value) {
    if (!(value instanceof List)) return outputPath.getText().trim();
    StringBuilder result = new StringBuilder();
    for (Object item : (List<?>) value) {
      if (result.length() > 0) result.append('\n');
      result.append(String.valueOf(item));
    }
    return result.length() == 0 ? outputPath.getText().trim() : result.toString();
  }

  private void chooseInstance() {
    JFileChooser chooser = new JFileChooser(instancePath.getText().trim());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      instancePath.setText(chooser.getSelectedFile().getAbsolutePath());
      inspectInstance();
    }
  }

  private void chooseOutput() {
    JFileChooser chooser = new JFileChooser(outputPath.getText().trim());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) outputPath.setText(chooser.getSelectedFile().getAbsolutePath());
  }

  private void chooseFile(JComboBox<String> target, boolean directories, String extension) {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(directories ? JFileChooser.FILES_AND_DIRECTORIES : JFileChooser.FILES_ONLY);
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    File selected = chooser.getSelectedFile();
    if (!directories && !selected.getName().toLowerCase().endsWith(extension)) {
      showError("请选择 " + extension + " 文件。");
      return;
    }
    target.getEditor().setItem(selected.getAbsolutePath());
    if (target == schematic) updateSchematicHint();
  }

  private void updateSchematicHint() {
    schematicNotice.setVisible(comboText(schematic).isEmpty());
    Container parent = schematicNotice.getParent();
    if (parent != null) {
      parent.revalidate();
      parent.repaint();
    }
  }

  private void setStatus(String message, boolean error) {
    status.setForeground(error ? ERROR : (dark ? DARK_TEXT : LIGHT_TEXT));
    status.setText(message);
    status.setCaretPosition(0);
  }

  private void showError(String message) {
    setStatus(message == null ? "发生未知错误。" : message, true);
    JOptionPane.showMessageDialog(this, message, "DsLR", JOptionPane.ERROR_MESSAGE);
  }

  private String cause(Exception error) {
    Throwable current = error;
    while (current.getCause() != null) current = current.getCause();
    return current.getMessage() == null ? current.toString() : current.getMessage();
  }

  private void applyTheme(boolean dark) {
    this.dark = dark;
    Color background = dark ? DARK_BG : LIGHT_BG;
    Color card = dark ? DARK_CARD : LIGHT_CARD;
    Color input = dark ? DARK_INPUT : LIGHT_INPUT;
    Color text = dark ? DARK_TEXT : LIGHT_TEXT;
    Color muted = dark ? DARK_MUTED : LIGHT_MUTED;
    SwingUtilities.updateComponentTreeUI(this);
    root.setBackground(background);
    for (CardPanel panel : cards) panel.setBackground(card);
    theme(root, background, card, input, text, muted);
    repaint();
  }

  private void updateColumns() {
    if (columns == null) return;
    int available = Math.max(0, getContentPane().getWidth() - 56);
    GridLayout layout;
    if (available >= 1260) layout = new GridLayout(1, 3, 16, 0);
    else if (available >= 820) layout = new GridLayout(2, 2, 16, 16);
    else layout = new GridLayout(3, 1, 0, 16);
    GridLayout current = columns.getLayout() instanceof GridLayout ? (GridLayout) columns.getLayout() : null;
    if (current == null || current.getRows() != layout.getRows() || current.getColumns() != layout.getColumns()) {
      columns.setLayout(layout);
      columns.revalidate();
      columns.repaint();
    }
  }

  private void theme(Component component, Color background, Color card, Color input, Color text, Color muted) {
    boolean night = background.equals(DARK_BG);
    component.setFont(component.getFont() == null ? FONT : component.getFont());
    if (component instanceof CardPanel) {
      component.setBackground(card);
      ((CardPanel) component).setBorder(BorderFactory.createCompoundBorder(new RoundedBorder(night ? new Color(35, 67, 99) : new Color(210, 218, 231), 14), BorderFactory.createEmptyBorder(16, 16, 16, 16)));
    } else if (component instanceof JTextComponent || component instanceof JComboBox || component instanceof JSpinner || component instanceof JScrollPane) component.setBackground(input);
    else if (component instanceof JPanel && ((JPanel) component).isOpaque()) component.setBackground(background);
    if (component instanceof JButton) {
      JButton button = (JButton) component;
      boolean primary = Boolean.TRUE.equals(button.getClientProperty("primary"));
      button.setBackground(primary ? ACCENT : input);
      button.setForeground(primary ? Color.WHITE : text);
      button.setBorder(new RoundedBorder(primary ? ACCENT : (night ? new Color(48, 82, 119) : new Color(196, 205, 220)), 9));
    } else if (component instanceof JLabel) {
      Object role = ((JLabel) component).getClientProperty("role");
      component.setForeground("muted".equals(role) ? muted : text);
    } else component.setForeground(text);
    if (component instanceof JTextComponent) {
      JTextComponent field = (JTextComponent) component;
      field.setBackground(input);
      field.setForeground(text);
      field.setCaretColor(text);
    }
    if (component instanceof JComboBox) {
      JComboBox<?> combo = (JComboBox<?>) component;
      combo.setBackground(input);
      combo.setForeground(text);
      if (combo.isEditable() && combo.getEditor().getEditorComponent() instanceof JTextComponent) {
        JTextComponent editor = (JTextComponent) combo.getEditor().getEditorComponent();
        editor.setBackground(input);
        editor.setForeground(text);
        editor.setCaretColor(text);
      }
    }
    if (component instanceof JSpinner && ((JSpinner) component).getEditor() instanceof JSpinner.DefaultEditor) {
      JTextField field = ((JSpinner.DefaultEditor) ((JSpinner) component).getEditor()).getTextField();
      field.setBackground(input);
      field.setForeground(text);
      field.setCaretColor(text);
    }
    if (component instanceof JScrollPane) {
      JScrollPane pane = (JScrollPane) component;
      pane.getViewport().setBackground(input);
      pane.getViewport().setOpaque(true);
    }
    if (component instanceof JCheckBox || component instanceof JRadioButton || component instanceof JSlider) {
      component.setForeground(text);
      if (component instanceof JComponent) ((JComponent) component).setOpaque(false);
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) theme(child, background, card, input, text, muted);
    }
  }

  private CardPanel card(String title) {
    CardPanel panel = new CardPanel(title);
    cards.add(panel);
    return panel;
  }

  private JPanel column() {
    JPanel panel = new JPanel();
    panel.setOpaque(false);
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    return panel;
  }

  private Component gap() {
    return Box.createVerticalStrut(16);
  }

  private JPanel row(JComponent input, JButton action, ActionListener listener) {
    action.addActionListener(listener);
    JPanel panel = new JPanel(new BorderLayout(8, 0));
    panel.setOpaque(false);
    panel.add(input, BorderLayout.CENTER);
    panel.add(action, BorderLayout.EAST);
    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
    return panel;
  }

  private JPanel two(Component left, Component right) {
    JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
    panel.setOpaque(false);
    panel.add(left);
    panel.add(right);
    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
    return panel;
  }

  private JPanel four(Component first, Component second, Component third, Component fourth) {
    JPanel panel = new JPanel(new GridLayout(1, 4, 5, 0));
    panel.setOpaque(false);
    panel.add(first);
    panel.add(second);
    panel.add(third);
    panel.add(fourth);
    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
    return panel;
  }

  private JPanel sliderRow(JSlider slider, JSpinner spinner) {
    JPanel panel = new JPanel(new BorderLayout(8, 0));
    panel.setOpaque(false);
    panel.add(slider, BorderLayout.CENTER);
    spinner.setPreferredSize(new Dimension(86, 34));
    panel.add(spinner, BorderLayout.EAST);
    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    return panel;
  }

  private JPanel field(String name, JTextField field) {
    JPanel panel = new JPanel(new BorderLayout(0, 4));
    panel.setOpaque(false);
    panel.add(label(name), BorderLayout.NORTH);
    panel.add(field, BorderLayout.CENTER);
    return panel;
  }

  private static JLabel label(String value) {
    JLabel label = new JLabel(value);
    label.setFont(FONT);
    label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
    return label;
  }

  private static JLabel mutedLabel(String value) {
    JLabel label = new JLabel(value);
    label.putClientProperty("role", "muted");
    label.setFont(FONT_SMALL);
    label.setBorder(BorderFactory.createEmptyBorder(4, 0, 7, 0));
    return label;
  }

  private static JButton button(String text, boolean primary) {
    JButton button = new JButton(text);
    button.setFont(new Font("SimHei", Font.BOLD, 12));
    button.setFocusPainted(false);
    button.setOpaque(true);
    button.putClientProperty("primary", Boolean.valueOf(primary));
    button.setBackground(primary ? ACCENT : new Color(235, 239, 247));
    button.setForeground(primary ? Color.WHITE : LIGHT_TEXT);
    button.setBorder(new RoundedBorder(primary ? ACCENT : new Color(196, 205, 220), 9));
    button.setPreferredSize(new Dimension(110, 36));
    button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
    return button;
  }

  private static JComboBox<String> editableCombo() {
    JComboBox<String> combo = new JComboBox<String>();
    combo.setEditable(true);
    combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
    return combo;
  }

  private static JTextField editor(JComboBox<String> combo) {
    return (JTextField) combo.getEditor().getEditorComponent();
  }

  private static String comboText(JComboBox<String> combo) {
    Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
    return item == null ? "" : item.toString().trim();
  }

  private static void refill(JComboBox<String> combo, List<String> values, String selected) {
    combo.removeAllItems();
    for (String value : values) combo.addItem(value);
    combo.getEditor().setItem(selected == null ? "" : selected);
  }

  private static ArrayList<String> pathStrings(List<Path> paths) {
    ArrayList<String> values = new ArrayList<String>();
    for (Path path : paths) values.add(path.toString());
    return values;
  }

  private static int positiveInt(String raw, String label) {
    try {
      int value = Integer.parseInt(raw.trim());
      if (value < 1 || value > 32768) throw new NumberFormatException();
      return value;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(label + "必须是 1 到 32768 的整数。");
    }
  }

  private void setResolution(String selectedWidth, String selectedHeight) {
    width.setText(selectedWidth);
    height.setText(selectedHeight);
  }

  private interface FileConsumer {
    void accept(File file);
  }

  private static void installDrop(JComponent component, final FileConsumer consumer) {
    component.setTransferHandler(new TransferHandler() {
      public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
      }

      @SuppressWarnings("unchecked")
      public boolean importData(TransferSupport support) {
        try {
          Transferable transferable = support.getTransferable();
          List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
          if (files.isEmpty()) return false;
          consumer.accept(files.get(0));
          return true;
        } catch (Exception ignored) {
          return false;
        }
      }
    });
  }

  private static final class CardPanel extends JPanel {
    private final JPanel body = new JPanel();

    CardPanel(String title) {
      super(new BorderLayout(0, 10));
      setBorder(BorderFactory.createCompoundBorder(new RoundedBorder(new Color(210, 218, 231), 14), BorderFactory.createEmptyBorder(16, 16, 16, 16)));
      JLabel heading = new JLabel(title);
      heading.setFont(FONT_TITLE);
      add(heading, BorderLayout.NORTH);
      body.setOpaque(false);
      body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
      add(body, BorderLayout.CENTER);
      setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    public Component add(Component component) {
      if (component == body || getComponentCount() < 2) return super.add(component);
      component.setFont(component.getFont() == null ? FONT : component.getFont());
      return body.add(component);
    }
  }

  private static final class RoundedBorder extends AbstractBorder {
    private final Color color;
    private final int radius;

    RoundedBorder(Color color, int radius) {
      this.color = color;
      this.radius = radius;
    }

    public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
      Graphics2D draw = (Graphics2D) graphics.create();
      draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      draw.setColor(color);
      draw.setStroke(new BasicStroke(1f));
      draw.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
      draw.dispose();
    }

    public Insets getBorderInsets(Component component) {
      return new Insets(1, 1, 1, 1);
    }
  }
}
