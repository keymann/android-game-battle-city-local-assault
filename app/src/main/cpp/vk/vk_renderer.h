#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "renderer.h"

namespace bc::vk {

// 2D 스프라이트 전용 Vulkan 렌더러.
//
// 파이프라인은 1개, 렌더패스는 1개다. 스프라이트는 CPU 에서 쿼드로 펼쳐
// 프레임당 하나의 호스트 가시 정점 버퍼에 쓰고, 텍스처가 바뀔 때만
// 디스크립터 셋을 다시 바인딩한다. (계획서 §25.1 Sprite Batch)
class VulkanRenderer final : public IRenderer {
public:
    VulkanRenderer() = default;
    ~VulkanRenderer() override;

    Backend backend() const override { return Backend::kVulkan; }

    bool onSurfaceCreated(ANativeWindow* window) override;
    void onSurfaceResized(int32_t width, int32_t height) override;
    void onSurfaceDestroyed() override;

    bool uploadTexture(int32_t textureId, int32_t width, int32_t height,
                       const uint8_t* pixels, bool nearest) override;
    void releaseTexture(int32_t textureId) override;

    void renderFrame(float clearR, float clearG, float clearB,
                     const float* sprites, int32_t spriteCount,
                     const int32_t* runs, int32_t runCount,
                     int32_t opaqueCount) override;

    int32_t surfaceWidth() const override { return static_cast<int32_t>(extent_.width); }
    int32_t surfaceHeight() const override { return static_cast<int32_t>(extent_.height); }

    // 기기가 Vulkan 을 실제로 쓸 수 있는지 가볍게 확인한다.
    static bool isSupported();

private:
    static constexpr int kFramesInFlight = 2;
    static constexpr int kMaxQuads = 8192;
    static constexpr int kMaxTextures = 64;

    struct Texture {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkDescriptorSet set = VK_NULL_HANDLE;
    };

    struct Frame {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkSemaphore imageAvailable = VK_NULL_HANDLE;
        VkFence inFlight = VK_NULL_HANDLE;
        VkBuffer vertexBuffer = VK_NULL_HANDLE;
        VkDeviceMemory vertexMemory = VK_NULL_HANDLE;
        void* vertexMapped = nullptr;
    };

    bool createInstance();
    bool pickPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createFramebuffers();
    bool createPipeline();
    bool createCommandsAndSync();
    bool createDescriptorInfra();
    bool recreateSwapchain();

    void destroySwapchain();
    void destroyEverything();

    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) const;
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                      VkMemoryPropertyFlags props, VkBuffer* outBuffer,
                      VkDeviceMemory* outMemory) const;
    VkCommandBuffer beginOneShot() const;
    void endOneShot(VkCommandBuffer cmd) const;

    ANativeWindow* window_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProps_{};
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{0, 0};
    VkSurfaceTransformFlagBitsKHR preTransform_ = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkSemaphore> renderFinished_;
    std::vector<VkFence> imagesInFlight_;

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    /** 지형처럼 불투명한 스프라이트 전용. 블렌딩을 꺼서 fill rate 를 아낀다. */
    VkPipeline pipelineOpaque_ = VK_NULL_HANDLE;
    /** 픽셀아트용. 도트가 뭉개지지 않는다. */
    VkSampler samplerNearest_ = VK_NULL_HANDLE;
    /** 벡터풍 스프라이트용. 회전해도 계단이 지지 않는다. */
    VkSampler samplerLinear_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;

    VkBuffer indexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory indexMemory_ = VK_NULL_HANDLE;

    Frame frames_[kFramesInFlight]{};
    uint32_t frameIndex_ = 0;

    std::unordered_map<int32_t, Texture> textures_;
    std::vector<float> scratch_;
    bool needsRecreate_ = false;
    bool ready_ = false;
};

}  // namespace bc::vk
