#include "socket_server.h"
#include "sysfs_monitor.h"
#include "thermal_core.h"
#include "battery_monitor.h"
#include "app_monitor.h"
#include "profile_engine.h"
#include "crypto.h"
#include "daemon.h"
#include "../lib/ipc_protocol.h"
#include "../lib/string_enc.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <time.h>

#define TAG "ZSocket"

#define MAX_CLIENTS     4
#define RECV_BUF_SIZE   (sizeof(zenith_header_t) + ZENITH_MAX_PAYLOAD)

static int g_server_fd = -1;
static volatile int g_running = 0;
static int g_client_fds[MAX_CLIENTS];
static int g_client_count = 0;

uint32_t zenith_crc32(const void *data, size_t len)
{
    const uint8_t *p = (const uint8_t *)data;
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= p[i];
        for (int j = 0; j < 8; j++) {
            crc = (crc >> 1) ^ (0xEDB88320 & (-(crc & 1)));
        }
    }
    return crc ^ 0xFFFFFFFF;
}

static int set_nonblocking(int fd)
{
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

/* --- send_response with partial-write loop --- */
static int send_response(int fd, uint8_t msg_type, uint16_t seq,
                         const void *payload, uint16_t payload_len)
{
    uint8_t buf[sizeof(zenith_header_t) + ZENITH_MAX_PAYLOAD];
    zenith_header_t *hdr = (zenith_header_t *)buf;

    hdr->version = ZENITH_MSG_VERSION;
    hdr->msg_type = msg_type;
    hdr->payload_len = payload_len;
    hdr->seq = seq;
    hdr->crc = 0;

    if (payload_len > 0 && payload)
        memcpy(buf + sizeof(zenith_header_t), payload, payload_len);

    hdr->crc = zenith_crc32(buf, sizeof(zenith_header_t) + payload_len);

    size_t total = sizeof(zenith_header_t) + payload_len;
    size_t sent = 0;
    while (sent < total) {
        ssize_t n = write(fd, buf + sent, total - sent);
        if (n < 0) {
            if (errno == EINTR) continue;
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "Send failed: %s", strerror(errno));
            return -1;
        }
        sent += (size_t)n;
    }
    return 0;
}

static int send_error(int fd, uint16_t seq, uint8_t code, const char *msg)
{
    error_payload_t err = {0};
    err.error_code = code;
    strncpy(err.message, msg, sizeof(err.message) - 1);
    return send_response(fd, MSG_ERROR, seq, &err, sizeof(err));
}

/* ---- Message handlers ---- */

static void handle_get_status(int fd, uint16_t seq)
{
    sysfs_snapshot_t snap;
    status_payload_t status = {0};

    sysfs_monitor_read(&snap);

    status.thermal_zone_count = (uint8_t)snap.zone_count;
    for (int i = 0; i < snap.zone_count && i < ZENITH_MAX_THERMAL_ZONES; i++) {
        status.zones[i].zone_id = (uint8_t)snap.zones[i].zone_id;
        status.zones[i].temp_milli = (uint32_t)snap.zones[i].temp_milli;
        status.zones[i].throttle = 0;
    }

    status.battery.online = (uint8_t)snap.battery.online;
    status.battery.capacity_pct_x10 = (uint32_t)snap.battery.capacity_pct * 10;
    status.battery.current_now_ua = snap.battery.current_now_ua;

    const battery_stats_t *bstat = battery_monitor_get_stats();
    status.battery.drain_pct_per_hr = (uint32_t)(bstat->drain_pct_per_hour * 10.0f);

    const char *prof = thermal_core_get_active_profile();
    status.current_profile_id = 0;
    if (prof) {
        /* Map string profile name to numeric ID */
        if (strcmp(prof, "balanced") == 0) status.current_profile_id = PROFILE_BALANCED;
        else if (strcmp(prof, "gaming") == 0) status.current_profile_id = PROFILE_GAMING;
        else if (strcmp(prof, "powersave") == 0) status.current_profile_id = PROFILE_POWERSAVE;
        else if (strcmp(prof, "battery_saver") == 0) status.current_profile_id = PROFILE_BATTERY_SAVER;
    }

    send_response(fd, MSG_GET_STATUS_R, seq, &status, sizeof(status));
}

static void handle_set_profile(int fd, uint16_t seq,
                               const set_profile_payload_t *payload)
{
    int result = thermal_core_apply_profile(payload->profile_id);
    if (result == 0)
        send_response(fd, MSG_SET_PROFILE_R, seq, payload,
                      sizeof(set_profile_payload_t));
    else
        send_error(fd, seq, 1, "Profile not found");
}

static void handle_get_apps(int fd, uint16_t seq)
{
    foreground_app_t fg;
    apps_payload_t apps = {0};

    if (app_monitor_detect_fg(&fg) == 0)
        strncpy(apps.packages, fg.package, sizeof(apps.packages) - 1);
    else
        strncpy(apps.packages, "unknown", sizeof(apps.packages) - 1);

    send_response(fd, MSG_GET_APPS_R, seq, &apps, sizeof(apps));
}

static void handle_get_apps_map(int fd, uint16_t seq)
{
    apps_map_payload_t resp;
    memset(&resp, 0, sizeof(resp));

    profile_map_entry_t entries[MAX_APPS_MAP];
    int n = profile_engine_export_map(entries, MAX_APPS_MAP);
    resp.count = (uint8_t)n;
    for (int i = 0; i < n; i++) {
        strncpy(resp.entries[i].pkg, entries[i].package,
                sizeof(resp.entries[i].pkg) - 1);
        resp.entries[i].pkg[sizeof(resp.entries[i].pkg) - 1] = '\0';
        resp.entries[i].profile_id = entries[i].profile_id;
    }

    send_response(fd, MSG_GET_APPS_MAP_R, seq, &resp, sizeof(resp));
}

static void handle_set_thermal(int fd, uint16_t seq,
                               const set_thermal_payload_t *payload)
{
    int result = thermal_core_set_limit(payload->zone_id,
                                        (int)payload->temp_limit_milli);
    if (result == 0)
        send_response(fd, MSG_SET_THERMAL_R, seq, payload,
                      sizeof(set_thermal_payload_t));
    else
        send_error(fd, seq, 2, "Invalid thermal zone");
}

static void handle_set_app_profile(int fd, uint16_t seq,
                                   const set_app_profile_payload_t *payload)
{
    char pkg[APP_PKG_LEN_IN];
    memcpy(pkg, payload->pkg, sizeof(pkg));
    pkg[sizeof(pkg) - 1] = '\0';
    int pid = payload->profile_id;

    int result = profile_engine_map_app(pkg, pid);
    if (result == 0) {
        send_response(fd, MSG_SET_APP_PROFILE_R, seq, payload,
                      sizeof(set_app_profile_payload_t));
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "Mapped app %s -> profile %d", pkg, pid);
    } else {
        send_error(fd, seq, 5, "Package map full");
    }
}

static void handle_message(int fd, zenith_header_t *hdr, const uint8_t *payload)
{
    uint16_t plen = hdr->payload_len;

    switch (hdr->msg_type) {
    case MSG_HEARTBEAT:
        send_response(fd, MSG_HEARTBEAT, hdr->seq, NULL, 0);
        break;

    case MSG_GET_STATUS:
        handle_get_status(fd, hdr->seq);
        break;

    case MSG_SET_PROFILE:
        if (plen >= sizeof(set_profile_payload_t))
            handle_set_profile(fd, hdr->seq,
                               (const set_profile_payload_t *)payload);
        else
            send_error(fd, hdr->seq, 3, "Invalid payload size");
        break;

    case MSG_GET_APPS:
        handle_get_apps(fd, hdr->seq);
        break;

    case MSG_GET_APPS_MAP:
        handle_get_apps_map(fd, hdr->seq);
        break;

    case MSG_SET_THERMAL:
        if (plen >= sizeof(set_thermal_payload_t))
            handle_set_thermal(fd, hdr->seq,
                               (const set_thermal_payload_t *)payload);
        else
            send_error(fd, hdr->seq, 4, "Invalid payload size");
        break;

    case MSG_SET_APP_PROFILE:
        if (plen >= sizeof(set_app_profile_payload_t))
            handle_set_app_profile(fd, hdr->seq,
                                   (const set_app_profile_payload_t *)payload);
        else
            send_error(fd, hdr->seq, 6, "Invalid payload size");
        break;

    case MSG_CHALLENGE: {
        /* HMAC challenge/response verification */
        if (plen < ZENITH_CHALLENGE_LEN) {
            send_error(fd, hdr->seq, 0x10, "Challenge too short");
            break;
        }
        uint8_t challenge[ZENITH_CHALLENGE_LEN];
        memcpy(challenge, payload, ZENITH_CHALLENGE_LEN);

        uint8_t my_challenge[ZENITH_CHALLENGE_LEN];
        crypto_generate_challenge(my_challenge, ZENITH_CHALLENGE_LEN);

        uint8_t my_response[32];
        crypto_generate_challenge(my_response, sizeof(my_response));
        /* For simplicity: client sends HMAC(challenge), daemon verifies */
        /* Here we just send our challenge back and expect HMAC response */
        send_response(fd, MSG_CHALLENGE_RSP, hdr->seq,
                      my_challenge, ZENITH_CHALLENGE_LEN);
        break;
    }

    default:
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Unknown msg_type: 0x%02x", hdr->msg_type);
        send_error(fd, hdr->seq, 0xFF, "Unknown message type");
        break;
    }
}

/* ---- Client management ---- */

static void add_client(int fd)
{
    if (g_client_count >= MAX_CLIENTS) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Max clients reached");
        close(fd);
        return;
    }
    set_nonblocking(fd);
    g_client_fds[g_client_count++] = fd;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Client connected (fd=%d)", fd);
}

static void remove_client(int idx)
{
    if (idx < 0 || idx >= g_client_count) return;
    close(g_client_fds[idx]);
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Client disconnected (fd=%d)", g_client_fds[idx]);
    for (int i = idx; i < g_client_count - 1; i++)
        g_client_fds[i] = g_client_fds[i + 1];
    g_client_count--;
}

static void read_client(int idx)
{
    uint8_t buf[RECV_BUF_SIZE];
    ssize_t n = read(g_client_fds[idx], buf, sizeof(buf));

    if (n <= 0) {
        if (n < 0 && errno == EAGAIN) return;
        remove_client(idx);
        return;
    }

    if (n < (ssize_t)sizeof(zenith_header_t)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Incomplete header");
        remove_client(idx);
        return;
    }

    zenith_header_t *hdr = (zenith_header_t *)buf;

    if (hdr->version != ZENITH_MSG_VERSION) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Bad version: %d", hdr->version);
        remove_client(idx);
        return;
    }

    uint32_t expected_crc = hdr->crc;
    hdr->crc = 0;
    uint32_t computed_crc = zenith_crc32(buf, n);
    if (computed_crc != expected_crc) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "CRC mismatch");
        send_error(g_client_fds[idx], hdr->seq, 0xFE, "CRC mismatch");
        return;
    }

    uint16_t plen = hdr->payload_len;
    if (n < (ssize_t)(sizeof(zenith_header_t) + plen)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Incomplete payload");
        remove_client(idx);
        return;
    }

    if (plen > ZENITH_MAX_PAYLOAD) {
        remove_client(idx);
        return;
    }

    handle_message(g_client_fds[idx], hdr, buf + sizeof(zenith_header_t));
}

/* ---- Public API ---- */

int socket_server_init(void)
{
    char path[MAX_PATH_LEN];
    struct sockaddr_un addr;

    USE(ENC("/dev/socket/zenithd"), path, sizeof(path));

    unlink(path);

    g_server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_server_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "socket() failed: %s", strerror(errno));
        return -1;
    }

    set_nonblocking(g_server_fd);

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    mkdir("/dev/socket", 0755);

    if (bind(g_server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "bind() failed: %s", strerror(errno));
        close(g_server_fd);
        g_server_fd = -1;
        return -1;
    }

    /* 0660: owner rw, group rw — SELinux (zenithd_socket) controls access */
    chmod(path, 0660);

    if (listen(g_server_fd, 8) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "listen() failed: %s", strerror(errno));
        close(g_server_fd);
        g_server_fd = -1;
        return -1;
    }

    g_running = 1;
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Socket server listening on %s", path);
    return 0;
}

int socket_server_run(void)
{
    if (g_server_fd < 0) return -1;

    struct pollfd fds[MAX_CLIENTS + 1];

    while (g_running) {
        int nfds = 0;

        fds[nfds].fd = g_server_fd;
        fds[nfds].events = POLLIN;
        nfds++;

        for (int i = 0; i < g_client_count; i++) {
            fds[nfds].fd = g_client_fds[i];
            fds[nfds].events = POLLIN;
            nfds++;
        }

        int ret = poll(fds, nfds, 1000);
        if (ret < 0) {
            if (errno == EINTR) continue;
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "poll() failed: %s", strerror(errno));
            break;
        }

        if (ret == 0) continue;

        if (fds[0].revents & POLLIN) {
            int client_fd = accept(g_server_fd, NULL, NULL);
            if (client_fd >= 0)
                add_client(client_fd);
        }

        for (int i = 0; i < g_client_count; ) {
            if (fds[i + 1].revents & (POLLIN | POLLERR | POLLHUP))
                read_client(i);
            else
                i++;
        }
    }

    return 0;
}

int socket_server_poll(int timeout_ms)
{
    if (g_server_fd < 0) return -1;

    struct pollfd fds[MAX_CLIENTS + 1];
    int nfds = 0;

    fds[nfds].fd = g_server_fd;
    fds[nfds].events = POLLIN;
    nfds++;

    for (int i = 0; i < g_client_count; i++) {
        fds[nfds].fd = g_client_fds[i];
        fds[nfds].events = POLLIN;
        nfds++;
    }

    int ret = poll(fds, nfds, timeout_ms);
    if (ret < 0) {
        if (errno == EINTR) return 0;
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "poll() failed: %s", strerror(errno));
        return -1;
    }

    if (ret == 0) return 0;

    if (fds[0].revents & POLLIN) {
        int client_fd = accept(g_server_fd, NULL, NULL);
        if (client_fd >= 0) {
            if (crypto_verify_apk_signature(client_fd) != 0) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "Client rejected (bad signature)");
                close(client_fd);
            } else {
                add_client(client_fd);
            }
        }
    }

    for (int i = 0; i < g_client_count; ) {
        if (fds[i + 1].revents & (POLLIN | POLLERR | POLLHUP))
            read_client(i);
        else
            i++;
    }

    return 0;
}

void socket_server_stop(void)
{
    g_running = 0;

    for (int i = 0; i < g_client_count; i++)
        close(g_client_fds[i]);
    g_client_count = 0;

    if (g_server_fd >= 0) {
        close(g_server_fd);
        g_server_fd = -1;
    }

    char path[MAX_PATH_LEN];
    USE(ENC("/dev/socket/zenithd"), path, sizeof(path));
    unlink(path);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Socket server stopped");
}

int socket_server_client_count(void)
{
    return g_client_count;
}
