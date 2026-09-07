/*
 * socket_server.h — Header for UNIX domain socket IPC server.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_SOCKET_SERVER_H
#define ZENITH_SOCKET_SERVER_H

#include <stdint.h>

/* Initialize and start the UNIX domain socket server */
int socket_server_init(void);

/* Run the socket event loop (blocking, call from main thread) */
int socket_server_run(void);

/* Single poll iteration with timeout_ms. Returns immediately after handling
 * events or after timeout. Used by zenithd main loop for non-blocking operation. */
int socket_server_poll(int timeout_ms);

/* Stop the server gracefully */
void socket_server_stop(void);

/* Get number of active client connections */
int socket_server_client_count(void);

#endif /* ZENITH_SOCKET_SERVER_H */
