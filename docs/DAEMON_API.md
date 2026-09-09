# ZenithThermal Daemon IPC API

## Socket Path

```
/dev/socket/zenithd
```

UNIX domain socket, stream type.

## Wire Protocol

All messages use a fixed header followed by a variable-length payload.

```c
struct zenith_header {
    uint8_t  version;      // ZENITH_MSG_VERSION (1)
    uint8_t  msg_type;     // Message type enum
    uint16_t payload_len;  // Bytes following header
    uint32_t seq;          // Sequence number
    uint32_t crc;          // CRC32 (over entire packet with crc=0)
};  // 12 bytes total
```

CRC32 is computed over the header (with crc field zeroed) plus the payload.

## Message Types

| Type   | Code  | Direction        | Description             |
|--------|-------|------------------|-------------------------|
| HEARTBEAT | 0x01 | bidirectional | Keep-alive              |
| GET_STATUS | 0x10 | app → daemon | Query thermal/battery   |
| GET_STATUS_R | 0x11 | daemon → app | Status response      |
| SET_PROFILE | 0x20 | app → daemon | Switch profile         |
| SET_PROFILE_R | 0x21 | daemon → app | Profile ack          |
| GET_APPS | 0x30 | app → daemon | Get foreground apps     |
| GET_APPS_R | 0x31 | daemon → app | Apps response          |
| SET_THERMAL | 0x40 | app → daemon | Set thermal limit     |
| SET_THERMAL_R | 0x41 | daemon → app | Thermal ack          |
| CHALLENGE | 0x50 | app → daemon | HMAC challenge         |
| CHALLENGE_RSP | 0x51 | daemon → app | HMAC response       |
| ERROR | 0xFF | daemon → app | Error response          |

## Payloads

### Status Response (GET_STATUS_R)
```c
struct status_payload {
    uint8_t  foreground_pid;
    uint32_t current_profile_id;
    uint8_t  thermal_zone_count;
    struct { uint8_t zone_id; uint32_t temp_milli; uint32_t throttle; } zones[16];
    struct {
        uint8_t  online;
        uint32_t capacity_pct_x10;   // percent * 10
        int32_t  current_now_ua;
        uint32_t drain_pct_per_hr;   // percent/hr * 10
    } battery;
};
```

### Set Profile (SET_PROFILE)
```c
struct set_profile_payload {
    char profile_id[32];  // null-terminated string ID
};
```

### Set Thermal Limit (SET_THERMAL)
```c
struct set_thermal_payload {
    uint8_t  zone_id;
    uint32_t temp_limit_milli;  // 0 = disable
};
```

### Error Response
```c
struct error_payload {
    uint8_t error_code;
    char    message[128];
};
```

## Usage from Kotlin

```kotlin
val socket = LocalSocket()
socket.connect(LocalSocketAddress("/dev/socket/zenithd",
    LocalSocketAddress.Namespace.FILESYSTEM))

// Send GET_STATUS
val header = ByteBuffer.allocate(12).apply {
    put(1)                    // version
    put(0x10)                 // msg_type = GET_STATUS
    putShort(0)               // payload_len
    putInt(seqNumber)         // seq
    putInt(0)                 // crc (placeholder)
}
// Set CRC before sending...
socket.outputStream.write(header.array())
```

## Error Codes

| Code | Description           |
|------|-----------------------|
| 0x01 | Profile not found     |
| 0x02 | Invalid thermal zone  |
| 0x03 | Invalid payload size  |
| 0xFE | CRC mismatch          |
| 0xFF | Unknown message type  |
