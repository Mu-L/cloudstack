// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.consoleproxy.vnc.network;

import java.nio.ByteBuffer;

public interface NioSocketHandler {

    // Getters
    NioSocketInputStream getInputStream();
    NioSocketOutputStream getOutputStream();

    // Read operations
    int readUnsignedInteger(int sizeInBits);
    void readBytes(ByteBuffer data, int length);
    String readString();
    byte[] readServerInit();
    int readAvailableDataIntoBuffer(ByteBuffer buffer, int maxSize);

    // Write operations
    void writeUnsignedInteger(int sizeInBits, int value);
    void writeBytes(byte[] data, int dataPtr, int length);
    void writeBytes(ByteBuffer data, int length);

    // Additional operations
    void waitForBytesAvailableForReading(int bytes);
    void flushWriteBuffer();
    void startTLSConnection(NioSocketSSLEngineManager sslEngineManager);
    boolean isTLSConnection();
}
