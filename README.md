# The Gallery

An image gallery for Android handhelds. The code was entirely vibe coded with AI; apart from the interaction design and this README, there is hardly any human work in this project.

## Why

Many gallery apps cannot be fully operated with a controller: you are constantly forced to reach for the touchscreen, and they often come with annoying media-management rules. So I made a gallery that can be operated entirely with a controller. No more touchscreen.

## Controller Controls

Follow the prompts in the lower-right corner. Short presses handle common actions; some less-frequent actions live in hold menus, opened with a button-and-direction chord.

- **A**: Open the selected image. Hold and press **Up** to delete, or **Left** to share.
- **X**: Cycle sorting. Hold and press **Up** to change sorting, or **Down** to change the thumbnail size.
- **Y**: Select images in the gallery; hold to select a range. In the viewer, hold to share.
- **B**: Go back. Hold with no selection to exit the app.
- **D-pad**: Move the selection or browse images. Hold **Up** and press **X** to jump to the top; hold **Down** and press **B** to jump to the bottom.
- **Start / Menu**: Open Settings.

![The Gallery grid with controller prompts](screenshots/gallery-grid.png)
![The A-button hold menu, with Delete and Share actions](screenshots/image.png)

If the hold delay feels too long, press the corresponding D-pad direction as soon as you start holding the button. It responds immediately—you do not need to wait for the D-pad wheel to appear.

Some actions are a little hidden: hold **Up** and press **X** to jump to the top, or hold **Down** and press **B** to jump to the bottom. This feels intuitive on a Nintendo Switch layout; I may add support for other button layouts in the future.

## Managing Your Gallery

The current setup is simple: add image sources in Settings to bring screenshot folders from different emulators together in one gallery. Each folder is scanned one level deep.

![Settings, where image folders can be added or removed](screenshots/settings_screen.png)

## Sharing Images

The Gallery can share selected images over your local network, so you can open them on a computer or phone and post them from there. Most people do not install social apps on their handhelds (at least I don't—it makes the device feel more like a game console).

If you use your own router, you can assign the handheld a static IP address. The sharing address will then stay the same, so you can bookmark it for next time.

This works pretty well, but I may add other ways to share images in the future.

## Recommended

### [iiSU](https://github.com/iisu-network/iiSU)

iiSU is a great-looking, easy-to-use gaming frontend (launcher), and it is what I use myself. I studied its visual style so that using iiSU and The Gallery together feels more immersive.
