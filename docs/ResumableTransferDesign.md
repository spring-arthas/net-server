# Resumable File Transfer Protocol Design

## 1. Overview
This document outlines the design for a breakpoint resumption protocol (Resumable Upload and Download) for the Net-Server project. The goal is to support reliable file transfers over unstable networks by allowing clients to resume transfers from where they left off, without modifying the existing codebase or logic.

## 2. Architecture
The solution introduces a parallel stack to the existing file upload/download mechanism:
*   **Port**: `10090` (New dedicated port for resumable transfers)
*   **Acceptor**: `ResumableFileAcceptor`
*   **Handler**: `ResumableFileHandler`
*   **Protocol**: Custom binary framing with JSON metadata.

This approach ensures strict isolation from existing functionality, satisfying the "no modification to existing code" requirement.

## 3. Protocol Specification

### 3.1 Frame Structure
All communications use a fixed 8-byte header followed by a variable-length payload.

```text
+--------+--------+--------+--------+----------------+
| Magic  |  Type  | Flags  | Length |      Data      |
| 2 bytes| 1 byte | 1 byte | 4 bytes|    N bytes     |
+--------+--------+--------+--------+----------------+
```

*   **Magic**: `0xFA 0xCE` (Consistent with project style)
*   **Type**:
    *   `0x10`: `UPLOAD_CHECK` (Client -> Server)
    *   `0x11`: `UPLOAD_DATA` (Client -> Server)
    *   `0x12`: `UPLOAD_ACK` (Server -> Client)
    *   `0x13`: `UPLOAD_END` (Client -> Server)
    *   `0x20`: `DOWNLOAD_CHECK` (Client -> Server)
    *   `0x21`: `DOWNLOAD_DATA` (Server -> Client)
    *   `0x22`: `DOWNLOAD_ACK` (Server -> Client)
    *   `0x23`: `DOWNLOAD_END` (Server -> Client)
*   **Flags**: `0x00` (Reserved)
*   **Length**: Big-endian integer of payload size.

### 3.2 Workflow

#### A. Resumable Upload
1.  **Check Phase**:
    *   **Client** sends `UPLOAD_CHECK`:
        ```json
        { "md5": "abc...", "fileName": "test.zip", "fileSize": 102400 }
        ```
    *   **Server** checks if file exists (partially or fully).
    *   **Server** responds `UPLOAD_ACK`:
        ```json
        { "status": "resume", "offset": 5000, "token": "uuid..." }
        ```
        *   `offset=0` implies new upload.
        *   `offset=fileSize` implies already complete.
2.  **Transfer Phase**:
    *   **Client** seeks to `offset` and sends `UPLOAD_DATA` frames.
    *   Payload is raw bytes.
    *   Server appends data to the file on disk.
3.  **Completion Phase**:
    *   **Client** sends `UPLOAD_END`.
    *   **Server** verifies size/MD5 and responds `UPLOAD_ACK` (`status: success`).

#### B. Resumable Download
1.  **Request Phase**:
    *   **Client** sends `DOWNLOAD_CHECK`:
        ```json
        { "fileId": 123, "startOffset": 5000 }
        ```
    *   **Server** verifies file and responds `DOWNLOAD_ACK`:
        ```json
        { "status": "ready", "fileSize": 102400 }
        ```
2.  **Transfer Phase**:
    *   **Server** reads file from `startOffset` and sends `DOWNLOAD_DATA` frames.
3.  **Completion Phase**:
    *   **Server** sends `DOWNLOAD_END`.

## 4. Technical Implementation Details

### 4.1 Sticky Packet & Half Packet Handling
*   The `FrameParser` (reused or reimplemented) reads the 4-byte `Length` field from the header.
*   It buffers incoming bytes until `Length` bytes are available.
*   This ensures every `handleFrame` call receives a complete, valid packet.

### 4.2 Server-Side Logic (`ResumableFileHandler`)
*   Maintains a `Map<String, ResumableContext>` keyed by Client IP/Port.
*   **On UPLOAD_CHECK**:
    *   Calculate file path based on MD5 (deduplication) or Name.
    *   Check `File.length()` on disk.
    *   Return length as offset.
    *   Open `RandomAccessFile` or `FileChannel` in `rw` mode, seek to end.
*   **On UPLOAD_DATA**:
    *   Write bytes to channel.
    *   Update context progress.

## 5. Usage
Start the server using `ResumableNetServer`. Run the client demo `ResumableFileClient`.
