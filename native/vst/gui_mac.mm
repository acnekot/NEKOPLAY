#import <Cocoa/Cocoa.h>
#include "vst_common.h"

@interface NvEditorWindow : NSObject <NSWindowDelegate>
@property (nonatomic, strong) NSWindow* window;
@property (nonatomic, strong) NSView* contentView;
@property (nonatomic, strong) NSTimer* idleTimer;
@property (nonatomic, assign) EditorCallbacks callbacks;
@property (nonatomic, assign) BOOL closed;
@end

@implementation NvEditorWindow
- (void)tickIdle:(NSTimer*)timer {
    if (!self.closed && self.callbacks.onIdle) {
        self.callbacks.onIdle(self.callbacks.userData);
    }
}
- (void)windowWillClose:(NSNotification*)notification {
    if (self.closed) return;
    self.closed = YES;
    [self.idleTimer invalidate];
    self.idleTimer = nil;
    if (self.callbacks.onClose) {
        self.callbacks.onClose(self.callbacks.userData);
    }
}
@end

static void runOnMainSync(void (^block)(void)) {
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

void* nvGuiCreateWindow(const char* title, int width, int height, bool alwaysOnTop, const EditorCallbacks& cb, void** outNativeView) {
    __block NvEditorWindow* holder = nil;
    __block void* viewPtr = nullptr;
    NSString* nsTitle = title ? [NSString stringWithUTF8String:title] : @"VST Plugin";
    runOnMainSync(^{
        NSRect frame = NSMakeRect(160, 160, width > 80 ? width : 640, height > 80 ? height : 420);
        NSWindowStyleMask style = NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable;
        NSWindow* win = [[NSWindow alloc] initWithContentRect:frame styleMask:style backing:NSBackingStoreBuffered defer:NO];
        [win setTitle:nsTitle];
        [win setReleasedWhenClosed:NO];
        [win setLevel:alwaysOnTop ? NSFloatingWindowLevel : NSNormalWindowLevel];
        NSView* cv = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, frame.size.width, frame.size.height)];
        [cv setAutoresizingMask:NSViewWidthSizable | NSViewHeightSizable];
        [win setContentView:cv];

        holder = [[NvEditorWindow alloc] init];
        holder.window = win;
        holder.contentView = cv;
        holder.callbacks = cb;
        holder.closed = NO;
        [win setDelegate:holder];
        if (cb.onIdle) {
            holder.idleTimer = [NSTimer scheduledTimerWithTimeInterval:1.0 / 30.0 target:holder selector:@selector(tickIdle:) userInfo:nil repeats:YES];
        }
        [win makeKeyAndOrderFront:nil];
        viewPtr = (__bridge void*)cv;
    });
    if (outNativeView) *outNativeView = viewPtr;
    return (__bridge_retained void*)holder;
}

void nvGuiResizeWindow(void* winHandle, int width, int height) {
    if (!winHandle || width <= 40 || height <= 40) return;
    NvEditorWindow* holder = (__bridge NvEditorWindow*)winHandle;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (holder.closed || !holder.window) return;
        [holder.window setContentSize:NSMakeSize(width, height)];
    });
}

void nvGuiSetOnTop(void* winHandle, bool alwaysOnTop) {
    if (!winHandle) return;
    NvEditorWindow* holder = (__bridge NvEditorWindow*)winHandle;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (holder.closed || !holder.window) return;
        [holder.window setLevel:alwaysOnTop ? NSFloatingWindowLevel : NSNormalWindowLevel];
        if (alwaysOnTop) [holder.window orderFront:nil];
    });
}

void nvGuiDestroyWindow(void* winHandle) {
    if (!winHandle) return;
    NvEditorWindow* holder = (__bridge_transfer NvEditorWindow*)winHandle;
    runOnMainSync(^{
        if (!holder.closed) {
            holder.closed = YES;
            [holder.idleTimer invalidate];
            holder.idleTimer = nil;
            [holder.window setDelegate:nil];
            [holder.window close];
        }
    });
}
