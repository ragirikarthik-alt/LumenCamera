package com.aicamera.photo.features.academy

import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.LessonIds

enum class Track(val title: String, val description: String) {
    COMPOSITION("Composition", "Where things go in the frame and why it matters."),
    LIGHT("Light", "Reading and using light: direction, quality and exposure."),
    PERSPECTIVE("Perspective", "Camera height, distance, foreground and lens choice."),
    TECHNICAL("Technical basics", "Focus, motion, shutter, ISO and exposure compensation."),
    STORYTELLING("Visual storytelling", "Layers, context, isolation and the decisive moment."),
}

enum class Diagram {
    THIRDS, SYMMETRY, LEADING_LINES, NEGATIVE_SPACE, FRAMING, DIAGONAL, FILL_FRAME, CLEAN_EDGES, HORIZON,
    GOLDEN_HOUR, BACKLIGHT, SIDE_LIGHT, HARSH_LIGHT, EXPOSURE,
    CAMERA_HEIGHT, DISTANCE, FOREGROUND, FOCAL_LENGTH,
    FOCUS, MOTION, SHUTTER_ISO, EXPOSURE_COMP,
    LAYERS, ISOLATION, CONTEXT, DECISIVE,
}

data class LessonSection(val heading: String, val body: String)

data class Practice(val title: String, val steps: List<String>, val strategy: CompositionStrategy? = null, val shots: Int = 2)

data class Lesson(
    val id: String,
    val track: Track,
    val title: String,
    val summary: String,
    val minutes: Int,
    val diagram: Diagram,
    val sections: List<LessonSection>,
    val tips: List<String>,
    val practice: Practice,
)

/** Short, visual, practice-oriented lessons. Each is linked from critique and coaching via [LessonIds]. */
object AcademyContent {

    val lessons: List<Lesson> = listOf(
        // ---------------- Composition
        Lesson(
            LessonIds.THIRDS, Track.COMPOSITION, "The rule of thirds",
            "Place your subject on a third line to create balance and movement.", 3, Diagram.THIRDS,
            listOf(
                LessonSection("What it is", "Imagine two horizontal and two vertical lines splitting the frame into nine parts. Placing the subject on a line - or where two lines cross - usually feels more dynamic than the dead centre."),
                LessonSection("Why it works", "An off-centre subject leaves space for the eye to travel. For people, put the eyes on the upper third line; for landscapes, put the horizon on a third, not in the middle."),
                LessonSection("When to break it", "Symmetry, bold graphic shapes and direct portraits often look stronger centred. The rule is a starting point, not a law."),
            ),
            listOf("Turn on the grid in Settings.", "Give a moving or looking subject space in front of it."),
            Practice("Two placements, one subject", listOf("Pick one subject.", "Shoot it centred.", "Shoot it on a third line using Guided mode.", "Compare both in the Library."), CompositionStrategy.RULE_OF_THIRDS),
        ),
        Lesson(
            LessonIds.SYMMETRY, Track.COMPOSITION, "Symmetry and centring",
            "Use mirror balance for calm, powerful images.", 3, Diagram.SYMMETRY,
            listOf(
                LessonSection("Find the axis", "Architecture, reflections and corridors often have a natural centre line. Stand exactly on it so both halves match."),
                LessonSection("Precision matters", "Symmetry only works when it's exact: keep the camera level and centred, and avoid tilting up or down, which makes verticals lean."),
                LessonSection("Add one disruption", "A single person or object breaking the symmetry can become the focal point."),
            ),
            listOf("Use the level indicator.", "Step left or right until both edges match."),
            Practice("Find a symmetric scene", listOf("Find a doorway, bridge or reflection.", "Centre it in Guided mode with the symmetry option.", "Shoot once level and once slightly off to compare."), CompositionStrategy.CENTERED_SYMMETRY),
        ),
        Lesson(
            LessonIds.LEADING_LINES, Track.COMPOSITION, "Leading lines",
            "Let roads, rails and edges guide the eye to your subject.", 3, Diagram.LEADING_LINES,
            listOf(
                LessonSection("Lines as paths", "The eye follows lines. Paths, fences, shorelines and shadows can lead from the frame edge to the subject."),
                LessonSection("Where they meet", "Lines that converge create depth. Put your subject at or near the meeting point."),
                LessonSection("Get low", "Lowering the camera makes lines on the ground longer and more dramatic."),
            ),
            listOf("Start lines from a corner of the frame.", "Avoid lines that lead out of the frame and away from the subject."),
            Practice("Follow the line", listOf("Find a path, rail or fence.", "Use Guided mode's leading lines option.", "Shoot standing, then crouching."), CompositionStrategy.LEADING_LINES),
        ),
        Lesson(
            LessonIds.NEGATIVE_SPACE, Track.COMPOSITION, "Negative space",
            "Empty space can make a small subject feel important.", 3, Diagram.NEGATIVE_SPACE,
            listOf(
                LessonSection("Less is more", "A plain sky, wall or sea around a small subject isolates it and creates mood."),
                LessonSection("Placement", "Put the subject off-centre and let the empty space sit in the direction it faces or moves."),
                LessonSection("Keep it clean", "Negative space must be calm: avoid bright spots or clutter that compete for attention."),
            ),
            listOf("Look for plain backgrounds first, then wait for a subject.", "Shoot wider than feels natural."),
            Practice("Small subject, big space", listOf("Find a plain background.", "Place a subject small and off-centre.", "Try the negative space option in Guided mode."), CompositionStrategy.NEGATIVE_SPACE),
        ),
        Lesson(
            LessonIds.FRAMING, Track.COMPOSITION, "Frame within a frame",
            "Use doorways, windows and branches to frame your subject.", 3, Diagram.FRAMING,
            listOf(
                LessonSection("Natural frames", "Arches, windows, gaps in foliage and even people's arms can surround the subject and add depth."),
                LessonSection("Expose for the subject", "The frame is often darker than what's inside it. Tap the subject so it's exposed well; a darker frame is fine."),
                LessonSection("Not too tight", "Leave some breathing room between the frame and the subject."),
            ),
            listOf("Step back to include the frame.", "Focus on the subject, not the frame."),
            Practice("Find a frame", listOf("Find a doorway, window or branches.", "Place a subject inside it.", "Shoot with the frame and without it."), CompositionStrategy.FRAME_WITHIN_FRAME),
        ),
        Lesson(
            LessonIds.DIAGONALS, Track.COMPOSITION, "Diagonals",
            "Diagonal lines add energy and movement.", 2, Diagram.DIAGONAL,
            listOf(
                LessonSection("Energy", "Horizontal lines feel calm, verticals feel strong, diagonals feel dynamic."),
                LessonSection("Corner to corner", "A diagonal running from one corner to the opposite one is especially strong."),
                LessonSection("Deliberate, not accidental", "A tilted horizon looks like a mistake; a strong diagonal subject looks intentional."),
            ),
            listOf("Change your angle so lines cross the frame diagonally.", "Stairs, roofs and shadows make great diagonals."),
            Practice("Make it diagonal", listOf("Find stairs, a railing or a roofline.", "Shoot it straight on, then angled so it runs corner to corner."), CompositionStrategy.DIAGONAL),
        ),
        Lesson(
            LessonIds.FILL_FRAME, Track.COMPOSITION, "Fill the frame",
            "Get closer so the subject dominates and distractions disappear.", 2, Diagram.FILL_FRAME,
            listOf(
                LessonSection("Get closer", "Most photos improve when you take two steps forward. Filling the frame removes clutter and shows detail."),
                LessonSection("Crop with your feet", "Moving closer is better than zooming digitally: it keeps quality and changes perspective naturally."),
                LessonSection("Mind the crop", "If you cut part of a subject off, do it boldly - avoid cutting at joints like wrists or knees."),
            ),
            listOf("Ask: what can I remove?", "Texture and pattern shots love a filled frame."),
            Practice("Two steps closer", listOf("Shoot a subject from where you stand.", "Take two steps closer and shoot again.", "Compare in the Library."), CompositionStrategy.FILL_THE_FRAME),
        ),
        Lesson(
            LessonIds.CLEAN_EDGES, Track.COMPOSITION, "Clean edges",
            "Check the borders before you shoot.", 2, Diagram.CLEAN_EDGES,
            listOf(
                LessonSection("Edge patrol", "Half-cut objects, bright spots and stray people at the frame edge pull the eye out of the picture."),
                LessonSection("Small moves", "A slight turn, a step, or a little zoom usually removes them."),
                LessonSection("Coaching helps", "Coach mode flags likely edge distractions - but you decide what belongs in your photo."),
            ),
            listOf("Run your eyes around all four edges before pressing the shutter."),
            Practice("Edge check", listOf("Frame a scene.", "Look only at the four edges.", "Reframe to remove anything that doesn't belong, then shoot."), CompositionStrategy.RULE_OF_THIRDS),
        ),
        Lesson(
            LessonIds.HORIZON, Track.COMPOSITION, "Level horizons",
            "A straight horizon makes a scene feel stable and intentional.", 2, Diagram.HORIZON,
            listOf(
                LessonSection("Why it matters", "Even a 2° tilt makes seas look like they're draining away. Viewers notice it instantly."),
                LessonSection("Use the level", "The level line in the viewfinder turns green when the camera is level."),
                LessonSection("Placement", "Put the horizon on the upper third to emphasise the foreground, or the lower third to emphasise the sky."),
            ),
            listOf("Tilt is fine when it's bold and deliberate - not when it's 3 degrees."),
            Practice("Level it", listOf("Find a horizon or a straight edge.", "Shoot it level, with the horizon on a third.", "Shoot it again on the other third."), CompositionStrategy.LAYERED_LANDSCAPE),
        ),

        // ---------------- Light
        Lesson(
            LessonIds.GOLDEN_HOUR, Track.LIGHT, "Golden hour",
            "Warm, low sunlight shortly after sunrise and before sunset.", 3, Diagram.GOLDEN_HOUR,
            listOf(
                LessonSection("Why it's loved", "Low sun gives soft, warm, directional light and long shadows that reveal texture."),
                LessonSection("Plan it", "It lasts roughly the first and last hour of sunlight, depending on season and location."),
                LessonSection("Try every direction", "Front-lit for colour, side-lit for texture, backlit for glow and silhouettes."),
            ),
            listOf("Arrive early - the best light can last minutes."),
            Practice("Same place, two times", listOf("Photograph a place at midday.", "Return during golden hour and shoot the same view.", "Compare the mood."), null, 2),
        ),
        Lesson(
            LessonIds.BACKLIGHT, Track.LIGHT, "Backlight",
            "Light from behind the subject: glow, rim light or silhouette.", 3, Diagram.BACKLIGHT,
            listOf(
                LessonSection("The trade-off", "With a bright sky behind, the camera can expose the sky (subject goes dark) or the subject (sky goes bright). You must choose."),
                LessonSection("Expose for the subject", "Tap the subject to expose for it, then check the sky. Accept some bright sky for a well-lit face."),
                LessonSection("Or embrace the silhouette", "Expose for the sky and let a strong subject shape go black."),
            ),
            listOf("Hide the sun behind the subject to reduce flare."),
            Practice("Two exposures", listOf("Put a subject in front of a bright sky.", "Tap the subject and shoot.", "Tap the sky and shoot a silhouette."), null, 2),
        ),
        Lesson(
            LessonIds.LIGHT_DIRECTION, Track.LIGHT, "Side light",
            "Light from the side reveals shape and texture.", 3, Diagram.SIDE_LIGHT,
            listOf(
                LessonSection("Direction changes everything", "Front light flattens, side light sculpts, back light separates."),
                LessonSection("Find it", "A window to one side of a person, or low sun to your left or right, creates side light."),
                LessonSection("Control contrast", "If shadows get too deep, turn the subject slightly towards the light."),
            ),
            listOf("Walk around your subject and watch how the light changes on it."),
            Practice("Front vs side", listOf("Light a subject from the front and shoot.", "Turn it so light comes from the side and shoot.", "Compare texture and shape."), null, 2),
        ),
        Lesson(
            LessonIds.HARSH_LIGHT, Track.LIGHT, "Harsh light",
            "Midday sun creates hard shadows - here's how to cope.", 2, Diagram.HARSH_LIGHT,
            listOf(
                LessonSection("The problem", "High sun causes squinting, dark eye sockets and bright highlights."),
                LessonSection("Find shade", "Open shade - under a tree or beside a building, facing the open sky - gives soft, even light."),
                LessonSection("Or use it", "Harsh light suits bold graphic shadows, black and white, and architecture."),
            ),
            listOf("Turn people so the sun is behind or beside them."),
            Practice("Sun vs shade", listOf("Photograph a person in direct sun.", "Move into open shade and photograph again."), null, 2),
        ),
        Lesson(
            LessonIds.EXPOSURE, Track.LIGHT, "Exposure basics",
            "Keeping detail in highlights and shadows.", 3, Diagram.EXPOSURE,
            listOf(
                LessonSection("What exposure is", "Exposure is how bright the photo is. Too bright loses highlight detail (clipping); too dark hides shadow detail."),
                LessonSection("Protect highlights", "Pure white areas have no detail and can't be recovered. Slightly darker is usually safer."),
                LessonSection("Expose for what matters", "Tap the most important part of the scene so the camera exposes for it."),
            ),
            listOf("The critique tells you exactly how much was clipped."),
            Practice("Find the limit", listOf("Shoot a bright scene normally.", "Lower exposure a little and shoot again.", "Check the critique for clipping."), null, 2),
        ),

        // ---------------- Perspective
        Lesson(
            LessonIds.CAMERA_HEIGHT, Track.PERSPECTIVE, "Camera height",
            "Shooting from low or high changes how a scene feels.", 3, Diagram.CAMERA_HEIGHT,
            listOf(
                LessonSection("Eye level is a habit", "Most photos are taken standing. Changing height instantly makes images less ordinary."),
                LessonSection("Low angle", "Crouching makes subjects look taller and stronger and includes more sky and foreground."),
                LessonSection("High angle", "Shooting from above shows patterns, layouts and food beautifully."),
            ),
            listOf("For children and pets, get down to their eye level."),
            Practice("Three heights", listOf("Choose a place.", "Photograph it from ground level, eye level and as high as you can."), null, 3),
        ),
        Lesson(
            LessonIds.DISTANCE, Track.PERSPECTIVE, "Distance and perspective",
            "Your position - not the zoom - shapes perspective.", 3, Diagram.DISTANCE,
            listOf(
                LessonSection("Near and far", "Close to a subject, near things look big and far things small. From further away, sizes look more similar."),
                LessonSection("Change position first", "Decide where to stand for the perspective you want, then pick the lens to frame it."),
                LessonSection("Portraits", "Very close wide-angle portraits distort faces; step back and use the main or telephoto lens."),
            ),
            listOf("Walk before you zoom."),
            Practice("Same framing, different distance", listOf("Frame a person close with the ultrawide lens.", "Step back and frame them the same size with telephoto.", "Compare the background."), null, 2),
        ),
        Lesson(
            LessonIds.FOREGROUND_DEPTH, Track.PERSPECTIVE, "Foreground and depth",
            "Layers make flat scenes feel three-dimensional.", 3, Diagram.FOREGROUND,
            listOf(
                LessonSection("Three layers", "A strong landscape often has a foreground, a middle ground and a background."),
                LessonSection("Find a foreground", "Rocks, flowers, paths or water at your feet give the eye an entry point."),
                LessonSection("Get close and low", "Use the ultrawide lens close to the foreground element so it looks prominent."),
            ),
            listOf("Put the horizon on the upper third when the foreground is interesting."),
            Practice("Add a foreground", listOf("Shoot a view as you see it.", "Find a foreground element and include it in the bottom third.", "Compare depth."), CompositionStrategy.LAYERED_LANDSCAPE),
        ),
        Lesson(
            LessonIds.FOCAL_LENGTH, Track.PERSPECTIVE, "Choosing a lens",
            "Ultrawide, main and telephoto each tell a different story.", 3, Diagram.FOCAL_LENGTH,
            listOf(
                LessonSection("Ultrawide", "Shows a lot and exaggerates depth. Great for interiors, big landscapes and dramatic foregrounds."),
                LessonSection("Main", "The most natural view and usually the best image quality."),
                LessonSection("Telephoto", "Isolates details, compresses distance so background looks closer, flatters portraits."),
            ),
            listOf("The lens chips are optical switches; pinch zoom beyond them is digital and loses quality."),
            Practice("One scene, three lenses", listOf("From one spot, shoot ultrawide, main and telephoto.", "Notice what each one emphasises."), null, 3),
        ),

        // ---------------- Technical
        Lesson(
            LessonIds.FOCUS, Track.TECHNICAL, "Focus",
            "Make sure the most important thing is sharp.", 2, Diagram.FOCUS,
            listOf(
                LessonSection("Tap to focus", "Tap the subject in the viewfinder; the camera focuses and exposes there."),
                LessonSection("Eyes first", "In portraits, the eyes must be sharp; a soft ear is fine, soft eyes are not."),
                LessonSection("Close-ups", "Very close subjects have a thin zone of focus. Move slowly and check."),
            ),
            listOf("Manual focus is available in Pro controls on supported lenses."),
            Practice("Focus choice", listOf("Place two objects at different distances.", "Shoot focusing on the near one, then the far one."), null, 2),
        ),
        Lesson(
            LessonIds.MOTION_BLUR, Track.TECHNICAL, "Motion and camera shake",
            "Hold steady and understand when blur happens.", 3, Diagram.MOTION,
            listOf(
                LessonSection("Two kinds of blur", "Camera shake blurs everything; subject motion blurs only what moves."),
                LessonSection("Hold steady", "Brace elbows against your body, exhale, and press the shutter gently. Lean on a wall in low light."),
                LessonSection("Use blur creatively", "A steady camera with a moving subject can show motion intentionally."),
            ),
            listOf("Coach mode warns you when the frame is moving."),
            Practice("Stable vs moving", listOf("Photograph a still subject while bracing.", "Photograph a moving subject (traffic, water).", "Compare the critique's sharpness."), null, 2),
        ),
        Lesson(
            LessonIds.SHUTTER_ISO, Track.TECHNICAL, "Shutter speed and ISO",
            "The trade-off between blur and noise.", 4, Diagram.SHUTTER_ISO,
            listOf(
                LessonSection("Shutter speed", "Fast shutters (1/500 s) freeze motion; slow ones (1/15 s) need a very steady hand."),
                LessonSection("ISO", "Higher ISO brightens dim scenes but adds noise. The camera balances both automatically."),
                LessonSection("Rule of thumb", "Keep the shutter faster than 1 / (2 × focal length) for sharp handheld shots."),
            ),
            listOf("Pro controls show ISO and shutter only when your lens supports manual control."),
            Practice("Freeze vs flow", listOf("In Pro controls, shoot moving water at a fast shutter.", "Shoot it again at a slower shutter while bracing."), null, 2),
        ),
        Lesson(
            LessonIds.EXPOSURE_COMP, Track.TECHNICAL, "Exposure compensation",
            "Tell the camera to go brighter or darker.", 2, Diagram.EXPOSURE_COMP,
            listOf(
                LessonSection("Why it's needed", "The camera aims for average brightness. Snow looks grey and black cats look washed out unless you correct it."),
                LessonSection("How", "Tap to focus, then drag the sun slider: + for brighter, − for darker."),
                LessonSection("Check", "After capture, the critique reports clipping and brightness."),
            ),
            listOf("Bright scenes often need +; dark scenes often need −."),
            Practice("Bright and dark", listOf("Shoot a bright scene at 0 and at +0.7.", "Shoot a dark scene at 0 and at −0.7."), null, 4),
        ),

        // ---------------- Storytelling
        Lesson(
            LessonIds.LAYERS, Track.STORYTELLING, "Layers",
            "Stack interest from front to back.", 3, Diagram.LAYERS,
            listOf(
                LessonSection("Depth tells stories", "Something near, something in the middle and something far turns a snapshot into a scene."),
                LessonSection("Overlap carefully", "Layers should overlap without merging - separate them with space or light."),
                LessonSection("Street and travel", "Frame a passer-by in front of a market stall in front of a skyline."),
            ),
            listOf("Move sideways to change how layers overlap."),
            Practice("Three layers", listOf("Find a scene with near, middle and far elements.", "Shoot it so all three are visible and separate."), CompositionStrategy.LAYERED_LANDSCAPE),
        ),
        Lesson(
            LessonIds.SEPARATION, Track.STORYTELLING, "Subject isolation",
            "Separate the subject from a busy background.", 3, Diagram.ISOLATION,
            listOf(
                LessonSection("Merges", "A pole or tree growing out of someone's head is the classic merge. The subject and background blend together."),
                LessonSection("Step sideways", "A small step left or right shifts the background behind the subject. Coach mode tells you which way."),
                LessonSection("Other tools", "Get closer, use telephoto, or place the subject against a plain area or contrasting light."),
            ),
            listOf("Look behind the subject, not just at it."),
            Practice("Fix the merge", listOf("Place a person in front of a pole or tree.", "Shoot the merge.", "Step sideways until they're separated and shoot again."), null, 2),
        ),
        Lesson(
            LessonIds.CONTEXT, Track.STORYTELLING, "Context",
            "Show where and why, not just what.", 2, Diagram.CONTEXT,
            listOf(
                LessonSection("Wide, medium, close", "A story of a place often needs a wide establishing view, a medium scene, and a close detail."),
                LessonSection("Include clues", "Signs, weather, tools or clothing tell viewers about the place and time."),
                LessonSection("Edit as a set", "Pick images that work together, not just individually."),
            ),
            listOf("Ask what a stranger would need to understand the moment."),
            Practice("Three-image sequence", listOf("Choose one place.", "Shoot a wide view, a medium scene and a close detail.", "View them together in the Library."), null, 3),
        ),
        Lesson(
            LessonIds.DECISIVE_MOMENT, Track.STORYTELLING, "The decisive moment",
            "Anticipate and press the shutter at the peak of action.", 3, Diagram.DECISIVE,
            listOf(
                LessonSection("Anticipate", "Compose first, then wait for the subject to enter the right place."),
                LessonSection("Peak action", "A jump at its highest point, a gesture at its widest, a glance at the camera."),
                LessonSection("Patience", "Stay a few minutes; scenes repeat and improve."),
            ),
            listOf("Use Guided mode to set the composition, then wait for the moment."),
            Practice("Wait for it", listOf("Frame an interesting background.", "Wait for a person to walk into the best spot.", "Shoot several frames as they pass."), CompositionStrategy.RULE_OF_THIRDS, 3),
        ),
    )

    private val byId = lessons.associateBy { it.id }

    fun lesson(id: String?): Lesson? = id?.let { byId[it] }
    fun forTrack(t: Track) = lessons.filter { it.track == t }
}
