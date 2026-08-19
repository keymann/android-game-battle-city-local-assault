#include "vk/vk_renderer.h"

#include <cmath>
#include <cstring>

#include "common/log.h"
#include "common/sprite.h"
#include "sprite.frag.h"
#include "sprite.vert.h"

namespace bc::vk {
namespace {

constexpr int kFloatsPerVertex = 8;  // pos(2) + uv(2) + color(4)
constexpr int kVerticesPerQuad = 4;
constexpr int kIndicesPerQuad = 6;

const char* const kDeviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};

}  // namespace

#define VK_FAIL(expr, msg)                                            \
    do {                                                              \
        VkResult _r = (expr);                                         \
        if (_r != VK_SUCCESS) {                                       \
            BC_LOGE("%s (VkResult=%d)", (msg), static_cast<int>(_r)); \
            return false;                                             \
        }                                                             \
    } while (0)

VulkanRenderer::~VulkanRenderer() { destroyEverything(); }

bool VulkanRenderer::isSupported() {
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "BattleCity";
    app.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;

    VkInstance probe = VK_NULL_HANDLE;
    if (vkCreateInstance(&info, nullptr, &probe) != VK_SUCCESS) {
        return false;
    }
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(probe, &count, nullptr);
    vkDestroyInstance(probe, nullptr);
    return count > 0;
}

// ---------------------------------------------------------------------------
// 초기화
// ---------------------------------------------------------------------------

bool VulkanRenderer::onSurfaceCreated(ANativeWindow* window) {
    window_ = window;
    if (window_ == nullptr) {
        return false;
    }
    if (!createInstance()) return false;

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = window_;
    VK_FAIL(vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_),
            "vkCreateAndroidSurfaceKHR 실패");

    if (!pickPhysicalDevice()) return false;
    if (!createDevice()) return false;
    if (!createSwapchain()) return false;
    if (!createRenderPass()) return false;
    if (!createFramebuffers()) return false;
    if (!createDescriptorInfra()) return false;
    if (!createPipeline()) return false;
    if (!createCommandsAndSync()) return false;

    ready_ = true;
    BC_LOGI("Vulkan 렌더러 준비 완료 (%ux%u)", extent_.width, extent_.height);
    return true;
}

bool VulkanRenderer::createInstance() {
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "BattleCity";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "BattleCityEngine";
    app.apiVersion = VK_API_VERSION_1_0;

    const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};

    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = 2;
    info.ppEnabledExtensionNames = extensions;

    VK_FAIL(vkCreateInstance(&info, nullptr, &instance_), "vkCreateInstance 실패");
    return true;
}

bool VulkanRenderer::pickPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) {
        BC_LOGE("Vulkan 물리 디바이스 없음");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    for (VkPhysicalDevice candidate : devices) {
        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());

        for (uint32_t i = 0; i < familyCount; ++i) {
            if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &present);
            if (present != VK_TRUE) continue;

            physicalDevice_ = candidate;
            queueFamily_ = i;
            vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memoryProps_);

            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physicalDevice_, &props);
            BC_LOGI("Vulkan 디바이스 선택: %s", props.deviceName);
            return true;
        }
    }
    BC_LOGE("그래픽 + 프레젠트를 모두 지원하는 큐 패밀리를 찾지 못함");
    return false;
}

bool VulkanRenderer::createDevice() {
    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily_;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkPhysicalDeviceFeatures features{};

    VkDeviceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queueInfo;
    info.enabledExtensionCount = 1;
    info.ppEnabledExtensionNames = kDeviceExtensions;
    info.pEnabledFeatures = &features;

    VK_FAIL(vkCreateDevice(physicalDevice_, &info, nullptr, &device_), "vkCreateDevice 실패");
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    return true;
}

bool VulkanRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps{};
    VK_FAIL(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps),
            "vkGetPhysicalDeviceSurfaceCapabilitiesKHR 실패");

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
    if (formatCount == 0) {
        BC_LOGE("서피스 포맷 없음");
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());

    VkSurfaceFormatKHR chosen = formats[0];
    for (const VkSurfaceFormatKHR& f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM &&
            f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
    }
    swapchainFormat_ = chosen.format;

    if (caps.currentExtent.width != 0xFFFFFFFFu) {
        extent_ = caps.currentExtent;
    } else {
        extent_.width = static_cast<uint32_t>(ANativeWindow_getWidth(window_));
        extent_.height = static_cast<uint32_t>(ANativeWindow_getHeight(window_));
    }
    if (extent_.width == 0 || extent_.height == 0) {
        BC_LOGW("서피스 크기가 0이라 스왑체인 생성을 건너뜀");
        return false;
    }

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }

    preTransform_ = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                            : caps.currentTransform;

    // FIFO 는 스펙상 항상 지원된다. 저사양 기기의 테어링/전력 소모를 피하기 위해
    // 기본값으로 쓴다. (계획서 §39 목표 FPS 60)
    VkSwapchainCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    info.surface = surface_;
    info.minImageCount = imageCount;
    info.imageFormat = swapchainFormat_;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent_;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = preTransform_;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if ((caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) == 0) {
        info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    info.oldSwapchain = VK_NULL_HANDLE;

    VK_FAIL(vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain_),
            "vkCreateSwapchainKHR 실패");

    uint32_t actual = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &actual, nullptr);
    swapchainImages_.resize(actual);
    vkGetSwapchainImagesKHR(device_, swapchain_, &actual, swapchainImages_.data());

    swapchainViews_.resize(actual);
    for (uint32_t i = 0; i < actual; ++i) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = swapchainImages_[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = swapchainFormat_;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        VK_FAIL(vkCreateImageView(device_, &viewInfo, nullptr, &swapchainViews_[i]),
                "스왑체인 이미지 뷰 생성 실패");
    }

    renderFinished_.resize(actual, VK_NULL_HANDLE);
    for (uint32_t i = 0; i < actual; ++i) {
        VkSemaphoreCreateInfo semInfo{};
        semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VK_FAIL(vkCreateSemaphore(device_, &semInfo, nullptr, &renderFinished_[i]),
                "renderFinished 세마포어 생성 실패");
    }
    imagesInFlight_.assign(actual, VK_NULL_HANDLE);
    return true;
}

bool VulkanRenderer::createRenderPass() {
    VkAttachmentDescription color{};
    color.format = swapchainFormat_;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorRef{};
    colorRef.attachment = 0;
    colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.srcAccessMask = 0;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &dep;

    VK_FAIL(vkCreateRenderPass(device_, &info, nullptr, &renderPass_),
            "vkCreateRenderPass 실패");
    return true;
}

bool VulkanRenderer::createFramebuffers() {
    framebuffers_.resize(swapchainViews_.size());
    for (size_t i = 0; i < swapchainViews_.size(); ++i) {
        VkImageView attachment = swapchainViews_[i];
        VkFramebufferCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        info.renderPass = renderPass_;
        info.attachmentCount = 1;
        info.pAttachments = &attachment;
        info.width = extent_.width;
        info.height = extent_.height;
        info.layers = 1;
        VK_FAIL(vkCreateFramebuffer(device_, &info, nullptr, &framebuffers_[i]),
                "vkCreateFramebuffer 실패");
    }
    return true;
}

bool VulkanRenderer::createDescriptorInfra() {
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &binding;
    VK_FAIL(vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &setLayout_),
            "디스크립터 셋 레이아웃 생성 실패");

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = kMaxTextures;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets = kMaxTextures;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    VK_FAIL(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_),
            "디스크립터 풀 생성 실패");

    // 픽셀아트 아틀라스와 벡터풍 유닛 아틀라스가 함께 있으므로 샘플러를 둘 만든다.
    //   NEAREST : 16px 타일. LINEAR 를 쓰면 도트가 뭉개지고 여백 없는 격자에서
    //             이웃 타일 색이 새어 나와 이음선이 생긴다.
    //   LINEAR  : 탱크처럼 회전하는 벡터풍 스프라이트. NEAREST 면 계단이 진다.
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;

    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    VK_FAIL(vkCreateSampler(device_, &samplerInfo, nullptr, &samplerNearest_),
            "NEAREST 샘플러 생성 실패");

    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    VK_FAIL(vkCreateSampler(device_, &samplerInfo, nullptr, &samplerLinear_),
            "LINEAR 샘플러 생성 실패");
    return true;
}

bool VulkanRenderer::createPipeline() {
    VkShaderModuleCreateInfo vertInfo{};
    vertInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    vertInfo.codeSize = kSpriteVertSpvSize;
    vertInfo.pCode = kSpriteVertSpv;
    VkShaderModule vertModule = VK_NULL_HANDLE;
    VK_FAIL(vkCreateShaderModule(device_, &vertInfo, nullptr, &vertModule),
            "정점 셰이더 모듈 생성 실패");

    VkShaderModuleCreateInfo fragInfo{};
    fragInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    fragInfo.codeSize = kSpriteFragSpvSize;
    fragInfo.pCode = kSpriteFragSpv;
    VkShaderModule fragModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device_, &fragInfo, nullptr, &fragModule) != VK_SUCCESS) {
        vkDestroyShaderModule(device_, vertModule, nullptr);
        BC_LOGE("프래그먼트 셰이더 모듈 생성 실패");
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertModule;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragModule;
    stages[1].pName = "main";

    VkVertexInputBindingDescription bindingDesc{};
    bindingDesc.binding = 0;
    bindingDesc.stride = kFloatsPerVertex * sizeof(float);
    bindingDesc.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription attrs[3]{};
    attrs[0].location = 0;
    attrs[0].binding = 0;
    attrs[0].format = VK_FORMAT_R32G32_SFLOAT;
    attrs[0].offset = 0;
    attrs[1].location = 1;
    attrs[1].binding = 0;
    attrs[1].format = VK_FORMAT_R32G32_SFLOAT;
    attrs[1].offset = 2 * sizeof(float);
    attrs[2].location = 2;
    attrs[2].binding = 0;
    attrs[2].format = VK_FORMAT_R32G32B32A32_SFLOAT;
    attrs[2].offset = 4 * sizeof(float);

    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &bindingDesc;
    vertexInput.vertexAttributeDescriptionCount = 3;
    vertexInput.pVertexAttributeDescriptions = attrs;

    VkPipelineInputAssemblyStateCreateInfo assembly{};
    assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    // 스프라이트는 프리멀티플라이드가 아닌 일반 알파 블렌딩을 쓴다.
    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.blendEnable = VK_TRUE;
    blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
    blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                     VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;

    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamicStates;

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
    pushRange.offset = 0;
    pushRange.size = 16 * sizeof(float);

    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &setLayout_;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        vkDestroyShaderModule(device_, vertModule, nullptr);
        vkDestroyShaderModule(device_, fragModule, nullptr);
        BC_LOGE("파이프라인 레이아웃 생성 실패");
        return false;
    }

    VkGraphicsPipelineCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewportState;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = pipelineLayout_;
    info.renderPass = renderPass_;
    info.subpass = 0;

    VkResult result =
            vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline_);

    // 지형처럼 화면을 꽉 채우는 불투명 스프라이트용. 블렌딩만 끈 같은 파이프라인이다.
    VkPipelineColorBlendAttachmentState opaqueAttachment = blendAttachment;
    opaqueAttachment.blendEnable = VK_FALSE;
    VkPipelineColorBlendStateCreateInfo opaqueBlend = blend;
    opaqueBlend.pAttachments = &opaqueAttachment;
    VkGraphicsPipelineCreateInfo opaqueInfo = info;
    opaqueInfo.pColorBlendState = &opaqueBlend;
    VkResult opaqueResult = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &opaqueInfo,
                                                      nullptr, &pipelineOpaque_);

    vkDestroyShaderModule(device_, vertModule, nullptr);
    vkDestroyShaderModule(device_, fragModule, nullptr);
    if (result != VK_SUCCESS || opaqueResult != VK_SUCCESS) {
        BC_LOGE("그래픽 파이프라인 생성 실패 (blend=%d opaque=%d)", static_cast<int>(result),
                static_cast<int>(opaqueResult));
        return false;
    }
    return true;
}

bool VulkanRenderer::createCommandsAndSync() {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily_;
    VK_FAIL(vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_),
            "커맨드 풀 생성 실패");

    // 인덱스 버퍼는 쿼드 패턴이 고정이라 한 번만 만든다. (계획서 §25.1)
    const VkDeviceSize indexBytes =
            static_cast<VkDeviceSize>(kMaxQuads) * kIndicesPerQuad * sizeof(uint16_t);
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (!createBuffer(indexBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      &staging, &stagingMemory)) {
        return false;
    }
    void* mapped = nullptr;
    vkMapMemory(device_, stagingMemory, 0, indexBytes, 0, &mapped);
    auto* indices = static_cast<uint16_t*>(mapped);
    for (int quad = 0; quad < kMaxQuads; ++quad) {
        const uint16_t base = static_cast<uint16_t>(quad * kVerticesPerQuad);
        const int offset = quad * kIndicesPerQuad;
        indices[offset + 0] = base + 0;
        indices[offset + 1] = base + 1;
        indices[offset + 2] = base + 2;
        indices[offset + 3] = base + 2;
        indices[offset + 4] = base + 3;
        indices[offset + 5] = base + 0;
    }
    vkUnmapMemory(device_, stagingMemory);

    if (!createBuffer(indexBytes,
                      VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &indexBuffer_, &indexMemory_)) {
        vkDestroyBuffer(device_, staging, nullptr);
        vkFreeMemory(device_, stagingMemory, nullptr);
        return false;
    }
    VkCommandBuffer copyCmd = beginOneShot();
    VkBufferCopy region{};
    region.size = indexBytes;
    vkCmdCopyBuffer(copyCmd, staging, indexBuffer_, 1, &region);
    endOneShot(copyCmd);
    vkDestroyBuffer(device_, staging, nullptr);
    vkFreeMemory(device_, stagingMemory, nullptr);

    const VkDeviceSize vertexBytes = static_cast<VkDeviceSize>(kMaxQuads) * kVerticesPerQuad *
                                     kFloatsPerVertex * sizeof(float);
    for (int i = 0; i < kFramesInFlight; ++i) {
        Frame& frame = frames_[i];

        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool = commandPool_;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = 1;
        VK_FAIL(vkAllocateCommandBuffers(device_, &allocInfo, &frame.cmd),
                "커맨드 버퍼 할당 실패");

        VkSemaphoreCreateInfo semInfo{};
        semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VK_FAIL(vkCreateSemaphore(device_, &semInfo, nullptr, &frame.imageAvailable),
                "imageAvailable 세마포어 생성 실패");

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VK_FAIL(vkCreateFence(device_, &fenceInfo, nullptr, &frame.inFlight),
                "인플라이트 펜스 생성 실패");

        if (!createBuffer(vertexBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                  VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                          &frame.vertexBuffer, &frame.vertexMemory)) {
            return false;
        }
        VK_FAIL(vkMapMemory(device_, frame.vertexMemory, 0, vertexBytes, 0, &frame.vertexMapped),
                "정점 버퍼 매핑 실패");
    }
    return true;
}

// ---------------------------------------------------------------------------
// 리소스 헬퍼
// ---------------------------------------------------------------------------

uint32_t VulkanRenderer::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) const {
    for (uint32_t i = 0; i < memoryProps_.memoryTypeCount; ++i) {
        const bool typeOk = (typeBits & (1u << i)) != 0;
        const bool propsOk = (memoryProps_.memoryTypes[i].propertyFlags & props) == props;
        if (typeOk && propsOk) return i;
    }
    return UINT32_MAX;
}

bool VulkanRenderer::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                                  VkMemoryPropertyFlags props, VkBuffer* outBuffer,
                                  VkDeviceMemory* outMemory) const {
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_FAIL(vkCreateBuffer(device_, &info, nullptr, outBuffer), "vkCreateBuffer 실패");

    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, *outBuffer, &req);

    const uint32_t typeIndex = findMemoryType(req.memoryTypeBits, props);
    if (typeIndex == UINT32_MAX) {
        BC_LOGE("적합한 메모리 타입 없음");
        return false;
    }

    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = typeIndex;
    VK_FAIL(vkAllocateMemory(device_, &alloc, nullptr, outMemory), "vkAllocateMemory 실패");
    VK_FAIL(vkBindBufferMemory(device_, *outBuffer, *outMemory, 0), "vkBindBufferMemory 실패");
    return true;
}

VkCommandBuffer VulkanRenderer::beginOneShot() const {
    VkCommandBufferAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    alloc.commandPool = commandPool_;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    vkAllocateCommandBuffers(device_, &alloc, &cmd);

    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);
    return cmd;
}

void VulkanRenderer::endOneShot(VkCommandBuffer cmd) const {
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence fence = VK_NULL_HANDLE;
    vkCreateFence(device_, &fenceInfo, nullptr, &fence);

    vkQueueSubmit(queue_, 1, &submit, fence);
    vkWaitForFences(device_, 1, &fence, VK_TRUE, UINT64_MAX);

    vkDestroyFence(device_, fence, nullptr);
    vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
}

// ---------------------------------------------------------------------------
// 텍스처
// ---------------------------------------------------------------------------

bool VulkanRenderer::uploadTexture(int32_t textureId, int32_t width, int32_t height,
                                   const uint8_t* pixels, bool nearest) {
    if (device_ == VK_NULL_HANDLE || pixels == nullptr || width <= 0 || height <= 0) {
        return false;
    }
    releaseTexture(textureId);

    const VkDeviceSize bytes = static_cast<VkDeviceSize>(width) * height * 4;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      &staging, &stagingMemory)) {
        return false;
    }
    void* mapped = nullptr;
    vkMapMemory(device_, stagingMemory, 0, bytes, 0, &mapped);
    std::memcpy(mapped, pixels, static_cast<size_t>(bytes));
    vkUnmapMemory(device_, stagingMemory);

    Texture texture{};

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &imageInfo, nullptr, &texture.image) != VK_SUCCESS) {
        vkDestroyBuffer(device_, staging, nullptr);
        vkFreeMemory(device_, stagingMemory, nullptr);
        BC_LOGE("텍스처 이미지 생성 실패 (id=%d)", textureId);
        return false;
    }

    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(device_, texture.image, &req);
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkAllocateMemory(device_, &alloc, nullptr, &texture.memory);
    vkBindImageMemory(device_, texture.image, texture.memory, 0);

    VkCommandBuffer cmd = beginOneShot();

    VkImageMemoryBarrier toTransfer{};
    toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.image = texture.image;
    toTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toTransfer.subresourceRange.levelCount = 1;
    toTransfer.subresourceRange.layerCount = 1;
    toTransfer.srcAccessMask = 0;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, nullptr, 0, nullptr, 1, &toTransfer);

    VkBufferImageCopy copy{};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    vkCmdCopyBufferToImage(cmd, staging, texture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                           &copy);

    VkImageMemoryBarrier toShader = toTransfer;
    toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &toShader);

    endOneShot(cmd);
    vkDestroyBuffer(device_, staging, nullptr);
    vkFreeMemory(device_, stagingMemory, nullptr);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = texture.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    vkCreateImageView(device_, &viewInfo, nullptr, &texture.view);

    VkDescriptorSetAllocateInfo setAlloc{};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = descriptorPool_;
    setAlloc.descriptorSetCount = 1;
    setAlloc.pSetLayouts = &setLayout_;
    if (vkAllocateDescriptorSets(device_, &setAlloc, &texture.set) != VK_SUCCESS) {
        BC_LOGE("디스크립터 셋 할당 실패 (id=%d)", textureId);
        return false;
    }

    VkDescriptorImageInfo imageDesc{};
    imageDesc.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imageDesc.imageView = texture.view;
    imageDesc.sampler = nearest ? samplerNearest_ : samplerLinear_;

    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = texture.set;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &imageDesc;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

    textures_[textureId] = texture;
    BC_LOGI("텍스처 업로드 완료 id=%d %dx%d", textureId, width, height);
    return true;
}

void VulkanRenderer::releaseTexture(int32_t textureId) {
    auto it = textures_.find(textureId);
    if (it == textures_.end()) return;
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        if (it->second.set != VK_NULL_HANDLE) {
            vkFreeDescriptorSets(device_, descriptorPool_, 1, &it->second.set);
        }
        vkDestroyImageView(device_, it->second.view, nullptr);
        vkDestroyImage(device_, it->second.image, nullptr);
        vkFreeMemory(device_, it->second.memory, nullptr);
    }
    textures_.erase(it);
}

// ---------------------------------------------------------------------------
// 프레임
// ---------------------------------------------------------------------------

void VulkanRenderer::onSurfaceResized(int32_t /*width*/, int32_t /*height*/) {
    needsRecreate_ = true;
}

void VulkanRenderer::renderFrame(float clearR, float clearG, float clearB, const float* sprites,
                                 int32_t spriteCount, const int32_t* runs, int32_t runCount,
                                 int32_t opaqueCount) {
    if (!ready_) return;
    if (needsRecreate_) {
        needsRecreate_ = false;
        if (!recreateSwapchain()) return;
    }

    Frame& frame = frames_[frameIndex_];
    vkWaitForFences(device_, 1, &frame.inFlight, VK_TRUE, UINT64_MAX);

    uint32_t imageIndex = 0;
    VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                             frame.imageAvailable, VK_NULL_HANDLE, &imageIndex);
    if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
        recreateSwapchain();
        return;
    }
    if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
        BC_LOGW("vkAcquireNextImageKHR=%d", static_cast<int>(acquire));
        return;
    }

    if (imagesInFlight_[imageIndex] != VK_NULL_HANDLE) {
        vkWaitForFences(device_, 1, &imagesInFlight_[imageIndex], VK_TRUE, UINT64_MAX);
    }
    imagesInFlight_[imageIndex] = frame.inFlight;

    // 스프라이트를 쿼드 정점으로 펼친다.
    const int32_t quadCount = spriteCount > kMaxQuads ? kMaxQuads : spriteCount;
    if (spriteCount > kMaxQuads) {
        BC_LOGW("스프라이트 %d개가 상한 %d개를 초과하여 잘림", spriteCount, kMaxQuads);
    }
    auto* vertices = static_cast<float*>(frame.vertexMapped);
    for (int32_t i = 0; i < quadCount; ++i) {
        const float* s = sprites + static_cast<size_t>(i) * kFloatsPerSprite;
        const float x = s[0];
        const float y = s[1];
        const float w = s[2];
        const float h = s[3];
        const float u0 = s[4];
        const float v0 = s[5];
        const float u1 = s[6];
        const float v1 = s[7];
        const float px = x + s[8] * w;
        const float py = y + s[9] * h;
        const float rot = s[10];
        const float r = s[12];
        const float g = s[13];
        const float b = s[14];
        const float a = s[15];

        float cornerX[4] = {x, x + w, x + w, x};
        float cornerY[4] = {y, y, y + h, y + h};
        if (rot != 0.0f) {
            const float c = std::cos(rot);
            const float sn = std::sin(rot);
            for (int k = 0; k < 4; ++k) {
                const float dx = cornerX[k] - px;
                const float dy = cornerY[k] - py;
                cornerX[k] = px + dx * c - dy * sn;
                cornerY[k] = py + dx * sn + dy * c;
            }
        }
        const float cornerU[4] = {u0, u1, u1, u0};
        const float cornerV[4] = {v0, v0, v1, v1};

        float* out = vertices + static_cast<size_t>(i) * kVerticesPerQuad * kFloatsPerVertex;
        for (int k = 0; k < 4; ++k) {
            out[k * kFloatsPerVertex + 0] = cornerX[k];
            out[k * kFloatsPerVertex + 1] = cornerY[k];
            out[k * kFloatsPerVertex + 2] = cornerU[k];
            out[k * kFloatsPerVertex + 3] = cornerV[k];
            out[k * kFloatsPerVertex + 4] = r;
            out[k * kFloatsPerVertex + 5] = g;
            out[k * kFloatsPerVertex + 6] = b;
            out[k * kFloatsPerVertex + 7] = a;
        }
    }

    vkResetCommandBuffer(frame.cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(frame.cmd, &begin);

    VkClearValue clear{};
    clear.color = {{clearR, clearG, clearB, 1.0f}};

    VkRenderPassBeginInfo passBegin{};
    passBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    passBegin.renderPass = renderPass_;
    passBegin.framebuffer = framebuffers_[imageIndex];
    passBegin.renderArea.extent = extent_;
    passBegin.clearValueCount = 1;
    passBegin.pClearValues = &clear;
    vkCmdBeginRenderPass(frame.cmd, &passBegin, VK_SUBPASS_CONTENTS_INLINE);

    if (quadCount > 0 && runCount > 0) {
        VkViewport viewport{};
        viewport.width = static_cast<float>(extent_.width);
        viewport.height = static_cast<float>(extent_.height);
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(frame.cmd, 0, 1, &viewport);

        VkRect2D scissor{};
        scissor.extent = extent_;
        vkCmdSetScissor(frame.cmd, 0, 1, &scissor);

        // 화면 픽셀 좌표(좌상단 원점) -> Vulkan NDC. 열 우선 mat4.
        const float sw = static_cast<float>(extent_.width);
        const float sh = static_cast<float>(extent_.height);
        const float mvp[16] = {2.0f / sw, 0.0f,      0.0f, 0.0f,
                               0.0f,      2.0f / sh, 0.0f, 0.0f,
                               0.0f,      0.0f,      1.0f, 0.0f,
                               -1.0f,     -1.0f,     0.0f, 1.0f};
        vkCmdPushConstants(frame.cmd, pipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(mvp),
                           mvp);

        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(frame.cmd, 0, 1, &frame.vertexBuffer, &offset);
        vkCmdBindIndexBuffer(frame.cmd, indexBuffer_, 0, VK_INDEX_TYPE_UINT16);

        int32_t drawn = 0;
        VkPipeline boundPipeline = VK_NULL_HANDLE;
        for (int32_t r = 0; r < runCount && drawn < quadCount; ++r) {
            const int32_t texId = runs[r * 2 + 0];
            int32_t count = runs[r * 2 + 1];
            if (drawn + count > quadCount) count = quadCount - drawn;
            if (count <= 0) continue;

            // 배치가 불투명 구간 경계에서 run 을 끊어 주므로 run 하나는 한쪽에만 속한다.
            VkPipeline wanted = (drawn < opaqueCount) ? pipelineOpaque_ : pipeline_;
            if (wanted != boundPipeline) {
                vkCmdBindPipeline(frame.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wanted);
                boundPipeline = wanted;
            }

            auto it = textures_.find(texId);
            if (it != textures_.end() && it->second.set != VK_NULL_HANDLE) {
                vkCmdBindDescriptorSets(frame.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                        pipelineLayout_, 0, 1, &it->second.set, 0, nullptr);
                vkCmdDrawIndexed(frame.cmd, static_cast<uint32_t>(count * kIndicesPerQuad), 1,
                                 static_cast<uint32_t>(drawn * kIndicesPerQuad),
                                 0, 0);
            }
            drawn += count;
        }
    }

    vkCmdEndRenderPass(frame.cmd);
    vkEndCommandBuffer(frame.cmd);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &frame.imageAvailable;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &frame.cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &renderFinished_[imageIndex];

    vkResetFences(device_, 1, &frame.inFlight);
    if (vkQueueSubmit(queue_, 1, &submit, frame.inFlight) != VK_SUCCESS) {
        BC_LOGE("vkQueueSubmit 실패");
        return;
    }

    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderFinished_[imageIndex];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &imageIndex;

    const VkResult presented = vkQueuePresentKHR(queue_, &present);
    if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) {
        needsRecreate_ = true;
    }

    frameIndex_ = (frameIndex_ + 1) % kFramesInFlight;
}

bool VulkanRenderer::recreateSwapchain() {
    if (device_ == VK_NULL_HANDLE) return false;
    vkDeviceWaitIdle(device_);
    destroySwapchain();
    if (!createSwapchain()) return false;
    if (!createFramebuffers()) return false;
    return true;
}

// ---------------------------------------------------------------------------
// 해제
// ---------------------------------------------------------------------------

void VulkanRenderer::destroySwapchain() {
    for (VkFramebuffer fb : framebuffers_) {
        if (fb != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, fb, nullptr);
    }
    framebuffers_.clear();
    for (VkSemaphore sem : renderFinished_) {
        if (sem != VK_NULL_HANDLE) vkDestroySemaphore(device_, sem, nullptr);
    }
    renderFinished_.clear();
    for (VkImageView view : swapchainViews_) {
        if (view != VK_NULL_HANDLE) vkDestroyImageView(device_, view, nullptr);
    }
    swapchainViews_.clear();
    swapchainImages_.clear();
    imagesInFlight_.clear();
    if (swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

void VulkanRenderer::onSurfaceDestroyed() { destroyEverything(); }

void VulkanRenderer::destroyEverything() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);

        for (auto& entry : textures_) {
            if (entry.second.view != VK_NULL_HANDLE) {
                vkDestroyImageView(device_, entry.second.view, nullptr);
            }
            if (entry.second.image != VK_NULL_HANDLE) {
                vkDestroyImage(device_, entry.second.image, nullptr);
            }
            if (entry.second.memory != VK_NULL_HANDLE) {
                vkFreeMemory(device_, entry.second.memory, nullptr);
            }
        }
        textures_.clear();

        for (Frame& frame : frames_) {
            if (frame.vertexMapped != nullptr) {
                vkUnmapMemory(device_, frame.vertexMemory);
                frame.vertexMapped = nullptr;
            }
            if (frame.vertexBuffer != VK_NULL_HANDLE) {
                vkDestroyBuffer(device_, frame.vertexBuffer, nullptr);
                frame.vertexBuffer = VK_NULL_HANDLE;
            }
            if (frame.vertexMemory != VK_NULL_HANDLE) {
                vkFreeMemory(device_, frame.vertexMemory, nullptr);
                frame.vertexMemory = VK_NULL_HANDLE;
            }
            if (frame.imageAvailable != VK_NULL_HANDLE) {
                vkDestroySemaphore(device_, frame.imageAvailable, nullptr);
                frame.imageAvailable = VK_NULL_HANDLE;
            }
            if (frame.inFlight != VK_NULL_HANDLE) {
                vkDestroyFence(device_, frame.inFlight, nullptr);
                frame.inFlight = VK_NULL_HANDLE;
            }
        }

        if (indexBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, indexBuffer_, nullptr);
            indexBuffer_ = VK_NULL_HANDLE;
        }
        if (indexMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, indexMemory_, nullptr);
            indexMemory_ = VK_NULL_HANDLE;
        }

        destroySwapchain();

        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline_, nullptr);
        if (pipelineOpaque_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, pipelineOpaque_, nullptr);
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        }
        if (renderPass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, renderPass_, nullptr);
        if (samplerNearest_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, samplerNearest_, nullptr);
        }
        if (samplerLinear_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, samplerLinear_, nullptr);
        }
        if (descriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
        }
        if (setLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
        }
        if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);

        pipeline_ = VK_NULL_HANDLE;
        pipelineOpaque_ = VK_NULL_HANDLE;
        pipelineLayout_ = VK_NULL_HANDLE;
        renderPass_ = VK_NULL_HANDLE;
        samplerNearest_ = VK_NULL_HANDLE;
        samplerLinear_ = VK_NULL_HANDLE;
        descriptorPool_ = VK_NULL_HANDLE;
        setLayout_ = VK_NULL_HANDLE;
        commandPool_ = VK_NULL_HANDLE;

        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }

    if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    window_ = nullptr;
    ready_ = false;
}

#undef VK_FAIL

}  // namespace bc::vk
