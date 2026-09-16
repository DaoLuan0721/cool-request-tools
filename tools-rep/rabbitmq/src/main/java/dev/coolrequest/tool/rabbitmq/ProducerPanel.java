package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.intellij.openapi.ui.ComboBox;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 生产面板：Exchange + RoutingKey + 类型 + 消息体 + Headers + 发送。
 */
public class ProducerPanel extends JPanel {

    private final Project project;
    private final ConnectionManager connectionManager;

    private final JBTextField exchangeField;
    private final JBTextField routingKeyField;
    private final ComboBox<BuiltinExchangeType> typeCombo;
    private final JBTextField contentTypeField;
    private final JBTextField headersField;
    private final EditorTextField bodyEditor;
    private final EditorTextField resultEditor;
    private final JButton sendButton;

    public ProducerPanel(Project project, ConnectionManager connectionManager) {
        this.project = project;
        this.connectionManager = connectionManager;
        setLayout(new BorderLayout(0, 4));
        setBorder(JBUI.Borders.empty(8));

        // Exchange / RoutingKey / Type
        JPanel topRow = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridy = 0;

        gbc.gridx = 0; gbc.weightx = 0;
        topRow.add(new JBLabel("Exchange:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        exchangeField = new JBTextField("");
        topRow.add(exchangeField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        topRow.add(new JBLabel("Type:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0;
        typeCombo = new ComboBox<>(new BuiltinExchangeType[]{BuiltinExchangeType.DIRECT, BuiltinExchangeType.TOPIC, BuiltinExchangeType.FANOUT, BuiltinExchangeType.HEADERS});
        topRow.add(typeCombo, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        topRow.add(new JBLabel("RoutingKey:"), gbc);
        gbc.gridx = 5; gbc.weightx = 1.0;
        routingKeyField = new JBTextField("");
        topRow.add(routingKeyField, gbc);

        // second row: Content-Type + Headers
        JPanel midRow = new JPanel(new GridBagLayout());
        GridBagConstraints g2 = new GridBagConstraints();
        g2.fill = GridBagConstraints.HORIZONTAL;
        g2.insets = JBUI.insets(2, 4);
        g2.anchor = GridBagConstraints.WEST;
        g2.gridy = 0;

        g2.gridx = 0; g2.weightx = 0;
        midRow.add(new JBLabel("ContentType:"), g2);
        g2.gridx = 1; g2.weightx = 0.5;
        contentTypeField = new JBTextField("text/plain");
        midRow.add(contentTypeField, g2);

        g2.gridx = 2; g2.weightx = 0;
        midRow.add(new JBLabel("Headers(json):"), g2);
        g2.gridx = 3; g2.weightx = 1.2;
        headersField = new JBTextField("");
        midRow.add(headersField, g2);

        JPanel north = new JPanel(new GridLayout(2, 1, 0, 2));
        north.add(topRow);
        north.add(midRow);
        add(north, BorderLayout.NORTH);

        // Body editor + send button
        bodyEditor = new EditorTextField("", project, com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE) {
            @Override
            protected EditorEx createEditor() {
                EditorEx editor = super.createEditor();
                setupEditorSettings(editor, true);
                return editor;
            }
        };
        bodyEditor.setOneLineMode(false);
        bodyEditor.setPlaceholder("Message body...");

        JPanel bodyPanel = new JPanel(new BorderLayout(0, 4));
        bodyPanel.add(bodyEditor, BorderLayout.CENTER);
        JPanel sendPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());
        sendPanel.add(sendButton);
        bodyPanel.add(sendPanel, BorderLayout.SOUTH);

        // Result editor
        resultEditor = new EditorTextField("", project, com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE) {
            @Override
            protected EditorEx createEditor() {
                EditorEx editor = super.createEditor();
                setupEditorSettings(editor, false);
                return editor;
            }
        };
        resultEditor.setOneLineMode(false);
        resultEditor.setEnabled(false);

        JBSplitter splitter = new JBSplitter(true, 0.65f);
        splitter.setFirstComponent(bodyPanel);
        splitter.setSecondComponent(resultEditor);
        splitter.setDividerWidth(3);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);
    }

    private void setupEditorSettings(EditorEx editor, boolean editable) {
        EditorSettings settings = editor.getSettings();
        settings.setLineNumbersShown(true);
        settings.setFoldingOutlineShown(false);
        settings.setAdditionalLinesCount(1);
        settings.setAdditionalColumnsCount(0);
        settings.setLineMarkerAreaShown(false);
        settings.setIndentGuidesShown(true);
        settings.setVirtualSpace(false);
        settings.setUseSoftWraps(true);
        settings.setGutterIconsShown(false);
        editor.setHorizontalScrollbarVisible(true);
        editor.setVerticalScrollbarVisible(true);
    }

    private void sendMessage() {
        RabbitConnection conn = connectionManager.getSelected();
        String exchange = exchangeField.getText().trim();
        String routingKey = routingKeyField.getText().trim();
        BuiltinExchangeType type = (BuiltinExchangeType) typeCombo.getSelectedItem();
        String body = bodyEditor.getText();
        String headersJson = headersField.getText().trim();
        String contentType = contentTypeField.getText().trim();

        if (body.isEmpty()) {
            setResultText("Error: Body is required.");
            return;
        }

        Map<String, Object> headers = parseHeaders(headersJson);
        if (headers == null && !headersJson.isEmpty()) {
            setResultText("Error: Headers(Json) 不是合法的 JSON 对象（示例 {\"foo\":\"bar\"}），已取消发送。\n收到内容: " + headersJson);
            return;
        }

        sendButton.setEnabled(false);
        setResultText("Connecting & sending...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long start = System.currentTimeMillis();
            Connection connection = null;
            try {
                connection = openConnection(conn);
                Channel channel = connection.createChannel();
                if (exchange != null && !exchange.isEmpty()) {
                    channel.exchangeDeclare(exchange, type, false, true, null);
                }
                AMQP.BasicProperties props = buildProperties(contentType, headers);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                // mandatory: 未路由到任何队列时 broker 以 basic.return 退回，据此区分「已路由/被丢弃」
                java.util.List<com.rabbitmq.client.Return> returned = new java.util.concurrent.CopyOnWriteArrayList<>();
                channel.addReturnListener(returned::add);
                channel.basicPublish(exchange, routingKey, true, props, payload);
                TimeUnit.MILLISECONDS.sleep(800);
                channel.close();
                long ms = System.currentTimeMillis() - start;
                boolean unroutable = !returned.isEmpty();
                SwingUtilities.invokeLater(() -> {
                    setResultText((unroutable
                            ? "Send 完成，但消息未被任何队列接收（已由 broker 退回）\n建议: 检查交换机与队列的绑定是否存在；headers 交换机核对 Headers(Json) 是否匹配绑定条件（如 x-match）\n"
                            : "Send OK（已路由到至少一个队列）\n")
                            + "Exchange: " + (exchange.isEmpty() ? "(default)" : exchange) + "  Type: " + type + "\n"
                            + "RoutingKey: " + (routingKey.isEmpty() ? "(default)" : routingKey) + "\n"
                            + "Bytes: " + payload.length + "\n"
                            + "Time: " + ms + " ms\n"
                            + "Host: " + conn.username + "@" + conn.host + ":" + conn.port);
                    sendButton.setEnabled(true);
                });
            } catch (Exception ex) {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                SwingUtilities.invokeLater(() -> {
                    setResultText("Send Failed:\n" + AmqpErrors.describe(ex) + "\n\n--- Stack Trace ---\n" + sw);
                    sendButton.setEnabled(true);
                });
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /**
     * 解析 Headers(Json)，非法输入返回 null（调用方据此取消发送，不再静默忽略）。
     */
    private Map<String, Object> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Object parsed = new com.google.gson.Gson().fromJson(headersJson, Object.class);
            if (!(parsed instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) parsed;
            return headers;
        } catch (Exception ex) {
            return null;
        }
    }

    private AMQP.BasicProperties buildProperties(String contentType, Map<String, Object> headers) {
        AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder();
        if (contentType != null && !contentType.isEmpty()) {
            b.contentType(contentType);
        }
        b.deliveryMode(2); // persistent
        if (headers != null && !headers.isEmpty()) {
            b.headers(new HashMap<>(headers));
        }
        return b.build();
    }

    private Connection openConnection(RabbitConnection conn) throws Exception {
        return RabbitClient.factory(conn).newConnection();
    }

    private void setResultText(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        resultEditor.setText(normalized);
    }
}