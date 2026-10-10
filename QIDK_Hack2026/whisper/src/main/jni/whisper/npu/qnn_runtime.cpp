#include "qnn_runtime.hpp"

#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <fstream>

#define TAG "NpuRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace npu {

namespace {

// ---- Qnn_Tensor_t comes in two layouts (v1, v2); these read and write the common parts ----

const char* tensorName(const Qnn_Tensor_t& t) {
    return t.version == QNN_TENSOR_VERSION_2 ? t.v2.name : t.v1.name;
}
uint32_t tensorRank(const Qnn_Tensor_t& t) {
    return t.version == QNN_TENSOR_VERSION_2 ? t.v2.rank : t.v1.rank;
}
const uint32_t* tensorDims(const Qnn_Tensor_t& t) {
    return t.version == QNN_TENSOR_VERSION_2 ? t.v2.dimensions : t.v1.dimensions;
}
Qnn_DataType_t tensorType(const Qnn_Tensor_t& t) {
    return t.version == QNN_TENSOR_VERSION_2 ? t.v2.dataType : t.v1.dataType;
}
void setTensorPointers(Qnn_Tensor_t& t, const char* name, uint32_t* dims) {
    if (t.version == QNN_TENSOR_VERSION_2) {
        t.v2.name = name;
        t.v2.dimensions = dims;
        t.v2.isDynamicDimensions = nullptr;
    } else {
        t.v1.name = name;
        t.v1.dimensions = dims;
    }
}
void setTensorBuffer(Qnn_Tensor_t& t, void* data, uint32_t size) {
    if (t.version == QNN_TENSOR_VERSION_2) {
        t.v2.memType = QNN_TENSORMEMTYPE_RAW;
        t.v2.clientBuf.data = data;
        t.v2.clientBuf.dataSize = size;
    } else {
        t.v1.memType = QNN_TENSORMEMTYPE_RAW;
        t.v1.clientBuf.data = data;
        t.v1.clientBuf.dataSize = size;
    }
}

size_t bytesPerElement(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
            return 4;
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
            return 2;
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_BOOL_8:
            return 1;
        default:
            return 0;
    }
}

// The tensor descriptors inside the binary info die with the system context, so keep our own copy.
void copyTensors(const Qnn_Tensor_t* src, uint32_t n, std::vector<std::string>* names,
                 std::vector<std::vector<uint32_t>>* dims, std::vector<size_t>* bytes,
                 std::vector<Qnn_Tensor_t>* out) {
    names->resize(n);
    dims->resize(n);
    bytes->resize(n);
    out->assign(src, src + n);
    for (uint32_t i = 0; i < n; i++) {
        (*names)[i] = tensorName(src[i]);
        const uint32_t* d = tensorDims(src[i]);
        (*dims)[i].assign(d, d + tensorRank(src[i]));
        size_t count = 1;
        for (uint32_t v : (*dims)[i]) count *= v;
        (*bytes)[i] = count * bytesPerElement(tensorType(src[i]));
    }
    // Only after the vectors stop growing is it safe to point into them.
    for (uint32_t i = 0; i < n; i++) {
        setTensorPointers((*out)[i], (*names)[i].c_str(), (*dims)[i].data());
    }
}

bool readFile(const std::string& path, std::vector<uint8_t>* out) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    std::streamsize size = f.tellg();
    f.seekg(0);
    out->resize(static_cast<size_t>(size));
    return static_cast<bool>(f.read(reinterpret_cast<char*>(out->data()), size));
}

}  // namespace

// ============================== Graph ==============================

int Graph::inputIndex(const std::string& name) const {
    auto it = inIdx_.find(name);
    return it == inIdx_.end() ? -1 : it->second;
}

int Graph::outputIndex(const std::string& name) const {
    auto it = outIdx_.find(name);
    return it == outIdx_.end() ? -1 : it->second;
}

void Graph::bindInput(int idx, void* data) {
    setTensorBuffer(in_[idx], data, static_cast<uint32_t>(inBytes_[idx]));
}

void Graph::bindOutput(int idx, void* data) {
    setTensorBuffer(out_[idx], data, static_cast<uint32_t>(outBytes_[idx]));
}

bool Graph::execute(std::string* err) {
    Qnn_ErrorHandle_t rc = rt_->qnn_.graphExecute(graph_, in_.data(), static_cast<uint32_t>(in_.size()),
                                                  out_.data(), static_cast<uint32_t>(out_.size()),
                                                  nullptr, nullptr);
    if (rc != QNN_SUCCESS) {
        if (err) *err = "graphExecute failed: " + std::to_string(rc);
        return false;
    }
    return true;
}

// ============================== Runtime ==============================

Runtime::~Runtime() {
    if (qnn_.contextFree) {
        for (auto c : contexts_) qnn_.contextFree(c, nullptr);
    }
    if (device_ && qnn_.deviceFree) qnn_.deviceFree(device_);
    if (backend_ && qnn_.backendFree) qnn_.backendFree(backend_);
    // The libraries stay loaded on purpose: unloading the HTP backend while the DSP session winds
    // down is a common source of crashes, and we only tear down at process exit.
}

bool Runtime::init(const std::string& backendLib, const std::string& systemLib, std::string* err) {
    backendLib_ = dlopen(backendLib.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!backendLib_) {
        if (err) *err = std::string("dlopen ") + backendLib + ": " + dlerror();
        return false;
    }
    systemLib_ = dlopen(systemLib.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!systemLib_) {
        if (err) *err = std::string("dlopen ") + systemLib + ": " + dlerror();
        return false;
    }

    using GetProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
    auto getProviders = reinterpret_cast<GetProviders>(dlsym(backendLib_, "QnnInterface_getProviders"));
    if (!getProviders) {
        if (err) *err = "QnnInterface_getProviders not found";
        return false;
    }
    const QnnInterface_t** providers = nullptr;
    uint32_t count = 0;
    if (getProviders(&providers, &count) != QNN_SUCCESS || count == 0) {
        if (err) *err = "no QNN interface providers";
        return false;
    }
    bool found = false;
    for (uint32_t i = 0; i < count; i++) {
        const auto& v = providers[i]->apiVersion.coreApiVersion;
        if (v.major == QNN_API_VERSION_MAJOR && v.minor >= QNN_API_VERSION_MINOR) {
            qnn_ = providers[i]->QNN_INTERFACE_VER_NAME;
            found = true;
            break;
        }
    }
    if (!found) {
        if (err) *err = "no QNN interface with a compatible API version";
        return false;
    }

    using GetSysProviders = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);
    auto getSysProviders =
        reinterpret_cast<GetSysProviders>(dlsym(systemLib_, "QnnSystemInterface_getProviders"));
    if (!getSysProviders) {
        if (err) *err = "QnnSystemInterface_getProviders not found";
        return false;
    }
    const QnnSystemInterface_t** sysProviders = nullptr;
    uint32_t sysCount = 0;
    if (getSysProviders(&sysProviders, &sysCount) != QNN_SUCCESS || sysCount == 0) {
        if (err) *err = "no QNN system providers";
        return false;
    }
    found = false;
    for (uint32_t i = 0; i < sysCount; i++) {
        const auto& v = sysProviders[i]->systemApiVersion;
        if (v.major == QNN_SYSTEM_API_VERSION_MAJOR && v.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
            sys_ = sysProviders[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
            found = true;
            break;
        }
    }
    if (!found) {
        if (err) *err = "no QNN system interface with a compatible API version";
        return false;
    }

    if (qnn_.backendCreate(nullptr, nullptr, &backend_) != QNN_SUCCESS) {
        if (err) *err = "backendCreate failed";
        return false;
    }
    if (qnn_.deviceCreate) {
        Qnn_ErrorHandle_t rc = qnn_.deviceCreate(nullptr, nullptr, &device_);
        if (rc != QNN_SUCCESS && rc != QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE) {
            if (err) *err = "deviceCreate failed: " + std::to_string(rc);
            return false;
        }
    }
    LOGI("QNN backend ready");
    return true;
}

std::unique_ptr<Graph> Runtime::loadContext(const std::string& binaryPath, std::string* err) {
    std::vector<uint8_t> blob;
    if (!readFile(binaryPath, &blob) || blob.empty()) {
        if (err) *err = "cannot read " + binaryPath;
        return nullptr;
    }

    // Read the graph's name and tensor layout out of the binary.
    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (sys_.systemContextCreate(&sysCtx) != QNN_SUCCESS) {
        if (err) *err = "systemContextCreate failed";
        return nullptr;
    }
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t infoSize = 0;
    if (sys_.systemContextGetBinaryInfo(sysCtx, blob.data(), blob.size(), &info, &infoSize) != QNN_SUCCESS ||
        !info) {
        sys_.systemContextFree(sysCtx);
        if (err) *err = "systemContextGetBinaryInfo failed (wrong QAIRT version for this binary?)";
        return nullptr;
    }

    uint32_t numGraphs = 0;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    switch (info->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            numGraphs = info->contextBinaryInfoV1.numGraphs;
            graphs = info->contextBinaryInfoV1.graphs;
            break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            numGraphs = info->contextBinaryInfoV2.numGraphs;
            graphs = info->contextBinaryInfoV2.graphs;
            break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            numGraphs = info->contextBinaryInfoV3.numGraphs;
            graphs = info->contextBinaryInfoV3.graphs;
            break;
        default:
            break;
    }
    if (numGraphs == 0 || !graphs) {
        sys_.systemContextFree(sysCtx);
        if (err) *err = "context binary has no graphs";
        return nullptr;
    }

    auto g = std::make_unique<Graph>();
    g->rt_ = this;
    const Qnn_Tensor_t *ins = nullptr, *outs = nullptr;
    uint32_t nIn = 0, nOut = 0;
    switch (graphs[0].version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            g->graphName_ = graphs[0].graphInfoV1.graphName;
            ins = graphs[0].graphInfoV1.graphInputs;   nIn = graphs[0].graphInfoV1.numGraphInputs;
            outs = graphs[0].graphInfoV1.graphOutputs; nOut = graphs[0].graphInfoV1.numGraphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            g->graphName_ = graphs[0].graphInfoV2.graphName;
            ins = graphs[0].graphInfoV2.graphInputs;   nIn = graphs[0].graphInfoV2.numGraphInputs;
            outs = graphs[0].graphInfoV2.graphOutputs; nOut = graphs[0].graphInfoV2.numGraphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            g->graphName_ = graphs[0].graphInfoV3.graphName;
            ins = graphs[0].graphInfoV3.graphInputs;   nIn = graphs[0].graphInfoV3.numGraphInputs;
            outs = graphs[0].graphInfoV3.graphOutputs; nOut = graphs[0].graphInfoV3.numGraphOutputs;
            break;
        default:
            sys_.systemContextFree(sysCtx);
            if (err) *err = "unknown graph info version";
            return nullptr;
    }
    copyTensors(ins, nIn, &g->inNames_, &g->inDims_, &g->inBytes_, &g->in_);
    copyTensors(outs, nOut, &g->outNames_, &g->outDims_, &g->outBytes_, &g->out_);
    for (uint32_t i = 0; i < nIn; i++) g->inIdx_[g->inNames_[i]] = static_cast<int>(i);
    for (uint32_t i = 0; i < nOut; i++) g->outIdx_[g->outNames_[i]] = static_cast<int>(i);
    sys_.systemContextFree(sysCtx);

    Qnn_ContextHandle_t ctx = nullptr;
    Qnn_ErrorHandle_t rc = qnn_.contextCreateFromBinary(backend_, device_, nullptr, blob.data(),
                                                        blob.size(), &ctx, nullptr);
    if (rc != QNN_SUCCESS) {
        if (err) *err = "contextCreateFromBinary failed: " + std::to_string(rc);
        return nullptr;
    }
    contexts_.push_back(ctx);

    if (qnn_.graphRetrieve(ctx, g->graphName_.c_str(), &g->graph_) != QNN_SUCCESS) {
        if (err) *err = "graphRetrieve failed for " + g->graphName_;
        return nullptr;
    }
    LOGI("loaded %s: graph %s, %u inputs, %u outputs", binaryPath.c_str(), g->graphName_.c_str(), nIn, nOut);
    return g;
}

}  // namespace npu
