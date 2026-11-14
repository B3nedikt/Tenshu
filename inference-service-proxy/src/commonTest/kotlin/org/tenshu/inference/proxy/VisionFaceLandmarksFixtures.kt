package org.tenshu.inference.proxy

/**
 * F1a's public-set Vision fixtures (`ios-face-grouping.md` §10.1, §10.3): one face per identity of
 * the eight in Vec2Face's `perturbation.png`, the one whose eye axis is most tilted, written by
 * `vision_detect.swift` (macOS 27.2, landmarks revision 3).
 *
 * ⚠⚠ These are the mapping's INPUT, not a golden of Vision: iOS Vision's points differ from macOS
 * Vision's (§11.3), and nothing here asserts what Vision answers. Each region is given back in
 * Vision's own convention — `pointsInImage` pixels, origin BOTTOM-left — so the y-flip is the
 * mapping's job, as it will be on the phone.
 *
 * - [Fixture.expected]: F1a's own `vision_five(nose="mid", eyes="corners")` (numpy, float64) on
 *   these points, in top-left image fractions. It is the reference the mapping was chosen with.
 * - [Fixture.mediaPipe]: the MediaPipe five F1a matched to the face (eye midpoints within 0.5 IOD),
 *   for a semantic check that the right regions became the right points.
 *
 * Generated from `~/tenshu-models/face-f1a-2026-11-09/public-fixtures`; aggregate landmark numbers
 * only, no pixels.
 */
internal object VisionFaceLandmarksFixtures {
    class Fixture(
        val name: String,
        val width: Int,
        val height: Int,
        val leftEye: FloatArray,
        val rightEye: FloatArray,
        val leftPupil: FloatArray,
        val rightPupil: FloatArray,
        val nose: FloatArray,
        val noseCrest: FloatArray,
        val outerLips: FloatArray,
        /** leftEye, rightEye, nose, mouthLeft, mouthRight — top-left image fractions. */
        val expected: List<FacePoint>,
        val mediaPipe: List<FacePoint>,
    )

    val faces = listOf(
        // id00_00.jpg: eye axis tilted 2.7 deg, landmarks confidence 0.920
        Fixture(
            name = "id00_00.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(47.380135f, 191.30386f, 60.39117f, 199.73145f, 80.72092f, 197.95721f, 91.73594f, 186.57256f, 78.05956f, 184.28085f, 59.50405f, 184.35477f),
            rightEye = floatArrayOf(192.71931f, 197.8833f, 179.41257f, 205.64555f, 159.45247f, 201.50569f, 149.10278f, 189.38176f, 162.85307f, 188.49466f, 181.48251f, 189.7514f),
            leftPupil = floatArrayOf(71.11049f, 193.81735f),
            rightPupil = floatArrayOf(173.35062f, 198.54863f),
            nose = floatArrayOf(121.60218f, 170.75232f, 98.01968f, 138.44652f, 98.83286f, 114.64224f, 111.474266f, 114.05083f, 123.81997f, 111.53733f, 135.50032f, 114.79009f, 147.32855f, 116.12076f, 146.29358f, 139.92505f),
            noseCrest = floatArrayOf(120.71506f, 192.19096f, 121.60218f, 170.75232f, 122.489296f, 148.7962f, 123.3764f, 127.35757f, 104.52519f, 125.287636f, 142.00586f, 126.17475f),
            outerLips = floatArrayOf(94.47121f, 83.44532f, 104.67305f, 88.17661f, 114.94881f, 90.98581f, 124.411385f, 89.35943f, 133.7261f, 91.57722f, 143.92793f, 89.35943f, 154.12976f, 85.66312f, 163.00093f, 81.37539f, 154.12976f, 68.2165f, 140.97087f, 58.90178f, 126.03776f, 55.205463f, 110.21752f, 57.275402f, 95.94974f, 65.85086f, 85.452194f, 77.97478f),
            expected = listOf(FacePoint(0.20701796f, 0.4376839f), FacePoint(0.50866383f, 0.4237127f), FacePoint(0.39557415f, 0.6462618f), FacePoint(0.25432202f, 0.76793224f), FacePoint(0.4851218f, 0.75781137f)),
            mediaPipe = listOf(FacePoint(0.20651504f, 0.4450946f), FacePoint(0.5084144f, 0.429925f), FacePoint(0.3643541f, 0.65198153f), FacePoint(0.2510252f, 0.7645272f), FacePoint(0.47931013f, 0.75877434f)),
        ),
        // id01_10.jpg: eye axis tilted 1.8 deg, landmarks confidence 0.916
        Fixture(
            name = "id01_10.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(97.681015f, 178.30205f, 109.50551f, 182.16776f, 125.72629f, 182.62254f, 137.3234f, 178.22624f, 125.49889f, 177.16508f, 109.50551f, 176.25551f),
            rightEye = floatArrayOf(237.0736f, 182.84993f, 225.0975f, 185.88185f, 209.17992f, 184.74489f, 198.5682f, 179.9696f, 210.24109f, 179.81801f, 226.00708f, 179.81801f),
            leftPupil = floatArrayOf(116.40313f, 180.95499f),
            rightPupil = floatArrayOf(218.57886f, 183.68372f),
            nose = floatArrayOf(172.0389f, 160.03473f, 142.09866f, 127.289986f, 141.64388f, 100.60907f, 157.63725f, 98.94152f, 173.40326f, 95.00002f, 186.7437f, 99.3963f, 200.69054f, 102.276634f, 198.87138f, 128.35115f),
            noseCrest = floatArrayOf(170.82611f, 184.2143f, 172.0389f, 160.03473f, 173.40326f, 135.24876f, 174.46442f, 111.06921f, 150.89124f, 112.43357f, 194.3235f, 112.58517f),
            outerLips = floatArrayOf(138.38457f, 63.922832f, 150.20905f, 68.16753f, 162.33673f, 70.74466f, 172.49367f, 68.7739f, 182.6506f, 71.351036f, 192.65594f, 69.53188f, 202.50969f, 66.348366f, 210.84747f, 61.1941f, 200.84213f, 49.975998f, 187.5017f, 42.54779f, 172.79686f, 39.819057f, 156.3487f, 41.335022f, 140.3553f, 47.550457f, 127.69704f, 57.85899f),
            expected = listOf(FacePoint(0.34970894f, 0.46945193f), FacePoint(0.64827645f, 0.46008995f), FacePoint(0.51766026f, 0.69334936f), FacePoint(0.38005072f, 0.82780063f), FacePoint(0.62752223f, 0.8178747f)),
            mediaPipe = listOf(FacePoint(0.3500011f, 0.47127467f), FacePoint(0.6471372f, 0.46346033f), FacePoint(0.52302814f, 0.68477553f), FacePoint(0.3826314f, 0.82523614f), FacePoint(0.6217152f, 0.815587f)),
        ),
        // id02_00.jpg: eye axis tilted 3.0 deg, landmarks confidence 0.928
        Fixture(
            name = "id02_00.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(56.689655f, 165.86517f, 67.84193f, 173.56091f, 85.97252f, 172.77829f, 97.71176f, 164.75647f, 85.25513f, 162.2782f, 68.29845f, 161.23471f),
            rightEye = floatArrayOf(190.71259f, 172.71307f, 178.19073f, 179.36531f, 160.19057f, 176.69136f, 150.147f, 167.56084f, 162.53842f, 166.58258f, 179.36467f, 167.23476f),
            leftPupil = floatArrayOf(75.341995f, 168.79999f),
            rightPupil = floatArrayOf(170.36458f, 173.887f),
            nose = floatArrayOf(123.60329f, 158.82164f, 102.2118f, 129.6692f, 103.12485f, 107.23422f, 114.537994f, 108.79945f, 125.69027f, 107.625534f, 136.58168f, 109.97338f, 147.9296f, 109.7125f, 147.14699f, 131.88661f),
            noseCrest = floatArrayOf(123.21198f, 174.9957f, 123.60329f, 158.82164f, 123.9946f, 142.32149f, 124.451126f, 126.27788f, 105.66835f, 120.40825f, 144.01653f, 122.495224f),
            outerLips = floatArrayOf(95.75522f, 78.66875f, 106.190094f, 83.75575f, 116.75541f, 87.5384f, 126.603325f, 85.97317f, 136.19035f, 88.45145f, 147.14699f, 86.23404f, 157.7123f, 82.581825f, 167.62541f, 78.01657f, 157.19054f, 65.886024f, 143.36433f, 57.40769f, 127.90768f, 54.016357f, 112.58145f, 55.19028f, 97.90741f, 61.842514f, 86.55949f, 72.40782f),
            expected = listOf(FacePoint(0.22976401f, 0.5080035f), FacePoint(0.50723153f, 0.49364f), FacePoint(0.34428364f, 0.660664f), FacePoint(0.2576175f, 0.78450054f), FacePoint(0.49888515f, 0.76780784f)),
            mediaPipe = listOf(FacePoint(0.23261702f, 0.50935507f), FacePoint(0.50976545f, 0.49643677f), FacePoint(0.37538874f, 0.6607019f), FacePoint(0.25995484f, 0.78106374f), FacePoint(0.49852234f, 0.7692912f)),
        ),
        // id03_00.jpg: eye axis tilted 12.2 deg, landmarks confidence 0.930
        Fixture(
            name = "id03_00.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(66.003296f, 197.6012f, 77.736015f, 200.25264f, 93.37963f, 196.14288f, 103.057465f, 188.7188f, 91.78875f, 189.11652f, 76.47657f, 191.96683f),
            rightEye = floatArrayOf(195.52716f, 169.4957f, 186.11447f, 176.98608f, 170.60342f, 179.50496f, 159.20215f, 176.65465f, 169.94055f, 172.41231f, 184.39102f, 168.70026f),
            leftPupil = floatArrayOf(82.84008f, 194.94972f),
            rightPupil = floatArrayOf(178.82295f, 174.26833f),
            nose = floatArrayOf(130.16864f, 174.00319f, 98.88141f, 159.81787f, 91.98761f, 139.40163f, 106.17293f, 133.83356f, 119.62908f, 126.54204f, 134.01326f, 128.13292f, 148.46373f, 128.53064f, 150.98262f, 148.68175f),
            noseCrest = floatArrayOf(134.14583f, 191.1714f, 130.16864f, 174.00319f, 126.19145f, 156.30467f, 122.41312f, 139.13647f, 103.256325f, 146.62686f, 141.83507f, 138.0759f),
            outerLips = floatArrayOf(80.65262f, 114.610466f, 93.843636f, 117.12935f, 106.96837f, 116.466484f, 116.71249f, 112.88702f, 127.11946f, 112.091576f, 138.65332f, 107.58409f, 148.86145f, 100.42515f, 155.4901f, 90.614746f, 143.55852f, 74.440834f, 126.058876f, 64.497856f, 106.17293f, 63.83499f, 87.6127f, 71.92194f, 74.819405f, 88.22843f, 70.046776f, 108.11439f),
            expected = listOf(FacePoint(0.2515785f, 0.42511907f), FacePoint(0.527871f, 0.48489532f), FacePoint(0.36018184f, 0.6046451f), FacePoint(0.20847255f, 0.678231f), FacePoint(0.46276817f, 0.73031324f)),
            mediaPipe = listOf(FacePoint(0.25188616f, 0.43344924f), FacePoint(0.5248786f, 0.49507117f), FacePoint(0.3563258f, 0.62121284f), FacePoint(0.20392595f, 0.68291354f), FacePoint(0.4700953f, 0.7350461f)),
        ),
        // id04_00.jpg: eye axis tilted 3.1 deg, landmarks confidence 0.928
        Fixture(
            name = "id04_00.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(36.16634f, 187.3202f, 48.082035f, 197.53365f, 68.431564f, 194.74817f, 79.96038f, 183.0646f, 66.49719f, 180.51125f, 48.159405f, 180.51125f),
            rightEye = floatArrayOf(183.41026f, 195.21242f, 168.86383f, 204.42f, 147.19893f, 199.93227f, 136.05698f, 186.31433f, 150.75816f, 185.23108f, 170.41133f, 187.08809f),
            leftPupil = floatArrayOf(56.747986f, 189.4867f),
            rightPupil = floatArrayOf(160.81686f, 195.98616f),
            nose = floatArrayOf(104.33339f, 174.2439f, 82.82324f, 143.68091f, 82.35899f, 121.24227f, 94.35206f, 120.15903f, 106.34512f, 117.21879f, 120.117805f, 120.93277f, 134.35475f, 122.32551f, 133.11674f, 145.30579f),
            noseCrest = floatArrayOf(104.41076f, 193.51018f, 104.33339f, 174.2439f, 104.17864f, 154.43599f, 104.17864f, 135.1697f, 86.69197f, 131.61047f, 125.53404f, 133.00322f),
            outerLips = floatArrayOf(72.45504f, 95.089645f, 84.75761f, 97.72038f, 97.13755f, 98.64888f, 108.35687f, 96.94663f, 119.80831f, 98.64888f, 136.05698f, 97.565636f, 152.61516f, 94.9349f, 168.24483f, 91.37566f, 154.93639f, 70.79401f, 134.97375f, 55.78333f, 110.75548f, 50.831352f, 89.013214f, 55.319084f, 71.9908f, 70.63926f, 62.009464f, 90.137665f),
            expected = listOf(FacePoint(0.17280762f, 0.44883212f), FacePoint(0.47539768f, 0.43225184f), FacePoint(0.3450583f, 0.62764585f), FacePoint(0.18455198f, 0.73173314f), FacePoint(0.50072867f, 0.7280486f)),
            mediaPipe = listOf(FacePoint(0.17111307f, 0.45286936f), FacePoint(0.47751933f, 0.4389931f), FacePoint(0.30569288f, 0.6358695f), FacePoint(0.19413118f, 0.7349167f), FacePoint(0.5028461f, 0.72643614f)),
        ),
        // id05_00.jpg: eye axis tilted 2.6 deg, landmarks confidence 0.910
        Fixture(
            name = "id05_00.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(52.602833f, 163.68806f, 64.86071f, 166.72093f, 81.73109f, 163.62488f, 91.52475f, 155.85313f, 79.1405f, 155.47403f, 62.522865f, 156.67453f),
            rightEye = floatArrayOf(183.52203f, 157.81186f, 171.01141f, 162.04526f, 153.31963f, 160.02333f, 142.32544f, 153.51527f, 155.0888f, 152.25159f, 172.40147f, 151.61974f),
            leftPupil = floatArrayOf(72.88519f, 162.80347f),
            rightPupil = floatArrayOf(166.4621f, 158.5069f),
            nose = floatArrayOf(113.89223f, 130.13686f, 92.85164f, 105.43154f, 91.01928f, 86.47606f, 101.19205f, 80.536674f, 111.61757f, 74.5973f, 123.74907f, 78.89387f, 135.62785f, 83.190445f, 135.24873f, 103.03052f),
            noseCrest = floatArrayOf(115.34548f, 153.4521f, 113.89223f, 130.13686f, 112.50216f, 106.31613f, 111.11208f, 82.937706f, 99.04376f, 87.73976f, 125.89737f, 86.34969f),
            outerLips = floatArrayOf(88.61825f, 62.59215f, 97.02185f, 61.581192f, 105.235886f, 60.443867f, 112.186226f, 58.295578f, 119.64206f, 59.432907f, 128.93024f, 59.18017f, 138.66072f, 58.927425f, 148.3912f, 59.18017f, 137.9025f, 50.460644f, 125.26551f, 44.52126f, 111.680756f, 42.878452f, 99.04376f, 46.037697f, 88.36551f, 53.872635f, 80.214645f, 63.72948f),
            expected = listOf(FacePoint(0.21447557f, 0.52449226f), FacePoint(0.48489207f, 0.53671557f), FacePoint(0.33144295f, 0.7655729f), FacePoint(0.23873407f, 0.8103289f), FacePoint(0.44164047f, 0.8238685f)),
            mediaPipe = listOf(FacePoint(0.21890232f, 0.5295317f), FacePoint(0.48613563f, 0.54008335f), FacePoint(0.3394469f, 0.77217036f), FacePoint(0.2441205f, 0.812033f), FacePoint(0.44217095f, 0.8239935f)),
        ),
        // id06_03.jpg: eye axis tilted 4.3 deg, landmarks confidence 0.867
        Fixture(
            name = "id06_03.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(86.26003f, 172.58553f, 100.58175f, 176.35844f, 121.52534f, 176.89745f, 135.38506f, 171.96954f, 120.67835f, 167.81163f, 100.27376f, 166.81064f),
            rightEye = floatArrayOf(241.64296f, 184.1353f, 228.39922f, 186.06027f, 210.53558f, 183.44232f, 200.21779f, 176.82043f, 213.30753f, 175.20348f, 231.17117f, 176.66646f),
            leftPupil = floatArrayOf(108.3586f, 173.5865f),
            rightPupil = floatArrayOf(218.69742f, 181.82533f),
            nose = floatArrayOf(180.96815f, 152.71991f, 149.3218f, 119.07159f, 150.01477f, 93.66209f, 165.87646f, 92.43011f, 181.43015f, 89.196175f, 191.13196f, 93.20009f, 200.52577f, 97.666f, 198.36981f, 121.22755f),
            noseCrest = floatArrayOf(177.88823f, 176.74344f, 180.96815f, 152.71991f, 184.5101f, 127.84941f, 187.12805f, 103.82588f, 162.95052f, 101.97792f, 197.90782f, 102.7479f),
            outerLips = floatArrayOf(137.07903f, 61.938713f, 151.70874f, 64.09467f, 166.49245f, 66.25063f, 175.57826f, 65.17265f, 184.3561f, 67.94459f, 191.43996f, 68.40658f, 198.67783f, 68.25259f, 205.29968f, 67.482605f, 198.06183f, 56.39482f, 187.59004f, 47.155003f, 174.80829f, 42.68909f, 155.94365f, 43.15108f, 137.84901f, 49.772953f, 122.52632f, 60.55274f),
            expected = listOf(FacePoint(0.329829f, 0.48726925f), FacePoint(0.6575309f, 0.46286348f), FacePoint(0.56449103f, 0.71436894f), FacePoint(0.36466166f, 0.8197835f), FacePoint(0.61101097f, 0.79915893f)),
            mediaPipe = listOf(FacePoint(0.32979476f, 0.48896757f), FacePoint(0.6601498f, 0.46732613f), FacePoint(0.5494107f, 0.717137f), FacePoint(0.3651258f, 0.8134703f), FacePoint(0.61073446f, 0.801296f)),
        ),
        // id07_00.jpg: eye axis tilted 7.0 deg, landmarks confidence 0.879
        Fixture(
            name = "id07_00.jpg", width = 336, height = 336,
            leftEye = floatArrayOf(36.25506f, 175.10254f, 43.866398f, 182.19836f, 57.876106f, 183.53262f, 67.701096f, 178.19559f, 58.23999f, 176.49745f, 45.807133f, 174.67801f),
            rightEye = floatArrayOf(151.27417f, 189.05159f, 139.75102f, 193.29697f, 124.467705f, 190.8104f, 114.8853f, 184.2604f, 125.80196f, 185.6553f, 140.35751f, 186.86827f),
            leftPupil = floatArrayOf(53.87333f, 179.89374f),
            rightPupil = floatArrayOf(138.41676f, 190.14326f),
            nose = floatArrayOf(80.86173f, 174.01088f, 64.12286f, 144.47525f, 64.1835f, 120.943794f, 75.03951f, 122.641945f, 86.07747f, 122.27805f, 100.81495f, 127.008606f, 116.098274f, 128.94934f, 112.21679f, 151.69238f),
            noseCrest = floatArrayOf(81.95339f, 190.08261f, 80.86173f, 174.01088f, 79.83071f, 157.51459f, 78.981636f, 141.56415f, 63.940914f, 132.22433f, 104.332535f, 140.47247f),
            outerLips = floatArrayOf(59.99879f, 95.714195f, 69.581184f, 103.71974f, 80.37655f, 108.45029f, 90.86867f, 108.328995f, 100.93625f, 111.60399f, 115.976974f, 110.75491f, 130.89641f, 107.72252f, 144.48157f, 102.50678f, 127.37881f, 96.68456f, 110.03346f, 92.803085f, 93.476524f, 89.40679f, 80.983025f, 86.37439f, 67.09462f, 84.43365f, 53.81268f, 85.525314f),
            expected = listOf(FacePoint(0.15469666f, 0.47425875f), FacePoint(0.39607063f, 0.4444762f), FacePoint(0.22324164f, 0.6212762f), FacePoint(0.16015679f, 0.7454604f), FacePoint(0.4300047f, 0.6949203f)),
            mediaPipe = listOf(FacePoint(0.15032914f, 0.48077497f), FacePoint(0.38739446f, 0.44693455f), FacePoint(0.23820351f, 0.5939174f), FacePoint(0.16058178f, 0.7546334f), FacePoint(0.422728f, 0.7068978f)),
        ),
    )
}
