// Thin wrapper over the Qualcomm AI Runtime (QNN) C API: load the HTP backend, load a precompiled
// context binary, bind caller-owned buffers to its tensors and run it.
//
// Written against the QAIRT 2.39 headers (not redistributed here; see whisper/third_party/qairt).
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

namespace npu {

class Runtime;

/** One graph of a context binary. Tensors are addressed by index; the caller owns the buffers. */
class Graph {
public:
    int inputIndex(const std::string& name) const;
    int outputIndex(const std::string& name) const;
    size_t inputBytes(int idx) const { return inBytes_[idx]; }
    size_t outputBytes(int idx) const { return outBytes_[idx]; }
    int numInputs() const { return static_cast<int>(in_.size()); }
    int numOutputs() const { return static_cast<int>(out_.size()); }
    const std::string& inputName(int idx) const { return inNames_[idx]; }
    const std::string& outputName(int idx) const { return outNames_[idx]; }

    /** Point a tensor at [data, data + size). The buffer must stay valid until the next execute() ends. */
    void bindInput(int idx, void* data);
    void bindOutput(int idx, void* data);

    bool execute(std::string* err);

private:
    friend class Runtime;
    Runtime* rt_ = nullptr;
    Qnn_GraphHandle_t graph_ = nullptr;
    std::string graphName_;
    std::vector<std::string> inNames_, outNames_;
    std::vector<std::vector<uint32_t>> inDims_, outDims_;
    std::vector<size_t> inBytes_, outBytes_;
    std::vector<Qnn_Tensor_t> in_, out_;
    std::unordered_map<std::string, int> inIdx_, outIdx_;
};

class Runtime {
public:
    Runtime() = default;
    ~Runtime();
    Runtime(const Runtime&) = delete;
    Runtime& operator=(const Runtime&) = delete;

    /** dlopens the HTP backend (e.g. libQnnHtp.so) and the system library (libQnnSystem.so). */
    bool init(const std::string& backendLib, const std::string& systemLib, std::string* err);

    /** Loads a precompiled context binary and returns its first graph. */
    std::unique_ptr<Graph> loadContext(const std::string& binaryPath, std::string* err);

private:
    friend class Graph;
    void* backendLib_ = nullptr;
    void* systemLib_ = nullptr;
    QNN_INTERFACE_VER_TYPE qnn_{};
    QNN_SYSTEM_INTERFACE_VER_TYPE sys_{};
    Qnn_BackendHandle_t backend_ = nullptr;
    Qnn_DeviceHandle_t device_ = nullptr;
    std::vector<Qnn_ContextHandle_t> contexts_;
};

}  // namespace npu
