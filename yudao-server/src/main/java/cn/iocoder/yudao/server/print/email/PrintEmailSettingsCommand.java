package cn.iocoder.yudao.server.print.email;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import static cn.iocoder.yudao.server.print.email.PrintEmailSettingsException.Reason.INVALID_INPUT;

public record PrintEmailSettingsCommand(long expectedVersion, String username, String password,
        String smtpHost, int smtpPort) {
    public static PrintEmailSettingsCommand parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("expectedVersion", "username", "password", "smtpHost", "smtpPort"))) throw invalid();
        JsonNode versionNode = body.get("expectedVersion");
        JsonNode usernameNode = body.get("username");
        JsonNode passwordNode = body.get("password");
        JsonNode hostNode = body.get("smtpHost");
        JsonNode portNode = body.get("smtpPort");
        if (!versionNode.isTextual() || !versionNode.textValue().matches("0|[1-9][0-9]{0,18}")
                || !usernameNode.isTextual() || !passwordNode.isTextual() || !hostNode.isTextual()
                || !portNode.isIntegralNumber() || !portNode.canConvertToInt()) throw invalid();
        long version;
        try { version = Long.parseLong(versionNode.textValue()); } catch (NumberFormatException e) { throw invalid(); }
        String username = usernameNode.textValue();
        String password = passwordNode.textValue();
        String host = hostNode.textValue();
        int port = portNode.intValue();
        if (!username.equals(username.trim()) || username.isEmpty() || username.length() > 320
                || !host.equals(host.trim()) || !host.matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,254}")
                || port < 1 || port > 65535 || password.getBytes(StandardCharsets.UTF_8).length > 1024
                || version == Long.MAX_VALUE) throw invalid();
        return new PrintEmailSettingsCommand(version, username, password, host, port);
    }
    private static PrintEmailSettingsException invalid() { return new PrintEmailSettingsException(INVALID_INPUT); }
}
