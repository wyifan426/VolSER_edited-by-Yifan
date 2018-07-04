import ij.*;
import ij.process.*;
import ij.gui.*;
import java.awt.*;
import ij.plugin.*;
import java.util.*;
import java.io.*;
import ij.io.*;
import javax.swing.*;
//import javax.swing.border.EmptyBorder;

import java.text.SimpleDateFormat;

//import java.awt.geom.*;


public class VolSER_v3_Neal implements PlugIn, DialogListener, ImageListener {
	double[][] topSpotsList;
	int numChosen;
	int[] voiGlobal;
	boolean repeatCallback;
	Roi globalXYroi, globalYZroi;
	int NOISETHRESHOLD;
	String SAVEPATH;
	String SAVEPATH_i;

	public void run(String arg) {
		SAVEPATH = "J:\\Breast Imaging\\Restricted-Data\\Partridge\\Neal\\HER2_results.csv";
		GenericDialog fpgd = new GenericDialog("Where to Save");
		fpgd.addStringField("Where to Save: ", SAVEPATH);
		fpgd.setLayout(new BoxLayout(fpgd, BoxLayout.Y_AXIS));
		fpgd.setPreferredSize(new Dimension(600,150));
		fpgd.showDialog();

		SAVEPATH = fpgd.getNextString();
		fpgd.dispose();

		//SAVEPATH = SAVEPATH.replaceAll("\\", "\\\");

		IJ.log(SAVEPATH);



		boolean anotherMeasurement = true;

		GenericDialog gd;
		GenericDialog menu;
		double noiseThreshold;
		double peThreshold;

		// -------------------------------------------------------------------------------------------------
		while (anotherMeasurement) {
			 menu = new GenericDialog("Choose an analytic process.");
			String[] options = {"Texture Features", "Original"};
			menu.addRadioButtonGroup("option", options,2, 1, "Original");
			menu.showDialog();
			String chosen = menu.getNextRadioButton();

			// user selects the image stack file
			OpenDialog odpre = new OpenDialog("Open pre-contrast series", "");
			String srcFilepath = odpre.getDirectory();

			// user selects the threshold
			gd = new GenericDialog("Params");
			gd.addStringField("PE Thold %", "150");
			gd.showDialog();

			peThreshold = Integer.parseInt(gd.getNextString()) / 100.0;

			IJ.log(srcFilepath);
			//IJ.log(srcFilepath);

			// gets files we are considering
			//String[] timeSeriesDirs = getSeriesList2(srcFilepath);
			//
		/*	ImagePlus plusSeries[] = new ImagePlus[timeSeriesDirs.length];
						for (int i = 0; i < plusSeries.length; i++) {
				plusSeries[i] = openImageStack(timeSeriesDirs[i], "timepoint " + i);
			}
			*/


			Map<Integer, File> map = getSeriesList(srcFilepath);

			ImagePlus[] plusSeries = new ImagePlus[map.size()];

			int index = 0;
			for (int i : map.keySet()) {

				plusSeries[index] = openImageStack(map.get(i), "timepoint" + i);
				index++;
			}



			if(chosen.equalsIgnoreCase("Texture Features")) {
				textureFeatures(plusSeries, peThreshold, srcFilepath);
				anotherMeasurement = IJ.showMessageWithCancel("Continue?", "Another Study?");
				if (!anotherMeasurement) {
					break;
				}
			} else {
				int[] voi = constructMIPS(plusSeries[1].getStack(), plusSeries[0].getStack());
				selectNoiseThreshold(plusSeries, voi);

			//	File parentFile = new File(srcFilepath).getParentFile();
				measureDCEparams(plusSeries, voi, srcFilepath, peThreshold);

			}
			for (int i = 0; i < plusSeries.length; i++) {
				plusSeries[i].close();
			}
			anotherMeasurement = IJ.showMessageWithCancel("Continue?", "Another Study?");
			if (!anotherMeasurement) {
				break;
			}

		}
		IJ.log("Closed");
	}

	public void textureFeatures(ImagePlus[] plusSeries, double peThreshold, String srcFilepath) {
		// select best image
		plusSeries[1].show();
		NonBlockingGenericDialog nbd = new NonBlockingGenericDialog("Select the Best Slice");
		nbd.showDialog();
		ImagePlus selected = null;
		if (nbd.wasOKed()) {
			int n = IJ.getImage().getCurrentSlice();
			ImageStack stack = new ImageStack(plusSeries[1].getWidth(), plusSeries[1].getHeight());
			for (int i = 0; i < plusSeries.length; i++) {
				stack.addSlice("Pre", plusSeries[i].getStack().getProcessor(n));
				//selected = new ImagePlus("Selected", plusSeries[1].getStack().getProcessor(n));
			}

			selected = new ImagePlus("Selected", stack);
			selected.show();
			plusSeries[1].close();

		}

		// threshold
		selectNoiseThreshold2D(selected);

		// select roi
		Roi roi = null;
		selected.setTitle("Select an Roi");
		while (!IJ.spaceBarDown() || roi == null) {
			roi = IJ.getImage().getRoi();
			IJ.wait(5);
		}

		Rectangle bounds = roi.getBounds();
		globalXYroi = roi;
	//	double[][] field = new double[bounds.width]
		//							 [bounds.height];


//		for (int x = bounds.x; x <= bounds.width; x++) {
//			for (int y = bounds.y; y <= bounds.height; y++) {
//				if (selected.getPixel(x, y)[0] > NOISETHRESHOLD) {
//					//field[x][y] = pixel[0];
//				}
//			}
//		}
//
		selected.close();
		// perform calculations
		measurements2D(plusSeries, bounds, selected, peThreshold, srcFilepath);
	//	selected.close();

	}

	//Yifan-moved saveMeasurements to here
	public void saveMeasurements(double[] results, ImagePlus ip0, String studyID,
			double noiseLevel, double maxPixel, double peThold) {

		String resFile = SAVEPATH;

		String header = ip0.getStack().getSliceLabel(1);
		String studyDate = header.substring(header.indexOf("Study Date") + 11,
				header.indexOf("0008,0021") - 1);
		String patientName = header.substring(
				header.indexOf("Patient's Name") + 15,
				header.indexOf("0010,0020") - 1);
		String patientID = header.substring(header.indexOf("Patient ID") + 11,header.indexOf("Patient ID") + 20);
		//String patientID = header.substring(header.indexOf("Patient ID") + 11,
		//		header.indexOf("0010,0021") - 1);

		String temp = "";
		String pixelSpacing = header.substring(header.indexOf("Pixel Spacing") +15, header.indexOf("Pixel Spacing") +22);
		//Yifan changed this for processing 4D HR
		if (header.contains("CAD~ REG 4D_HR")){
			pixelSpacing = header.substring(header.indexOf("Pixel Spacing") +15, header.indexOf("Pixel Spacing") +19);
		}
		//IJ.log("pixel spacing: "+pixelSpacing);
		double pixSpacing = Double.parseDouble(pixelSpacing);
		double sliceSpacing = 0.65;
		
		double voxelVol = (pixSpacing*pixSpacing*sliceSpacing)/1000; //cc's

		try {
			File myDir = new File(resFile);
			if (!myDir.exists()) {
				boolean worked = myDir.createNewFile(); // creates file, if it
														// doesn't exist
				if (!worked) {
					System.err
							.println("File " + myDir + "could not be created");
					return;
				}
				temp = "StudyID,Name,MRN,Study Date,Measurement Date, KPACS filepath,Noise Level,Noise Level as % of Max Pixel,PEthreshold, Mean PE, Mean SER,Peak PE,Peak SER,VOE2(cc),VOE3(cc),VOE4(cc),"
						+ "InitialRapidEnhancement%,InitialMediumEnhancement%,CombinedPersistVol%,CombinedPlateauVol%,CombinedwashoutVol%";
				temp += "break";
			} else {
				temp = "";
			}
			Scanner console = new Scanner(myDir);
			while (console.hasNextLine()) {
				temp += console.nextLine();
				temp += "break";
			}
			console.close();
			// myFile.createNewFile(); // If it doesn't exist, create it
			PrintStream ps = new PrintStream(new BufferedOutputStream(
					new FileOutputStream(myDir)));
			String[] temp1 = temp.split("break");
			for (int i = 0; i < temp1.length; i++) {
				ps.println(temp1[i]);
			}
			// results: double[] results = {meanPce, meanSer, peakPCE,
			// peakSER,(double)volumeEnhancement1,
			// (double)volumeEnhancement2,(double)volumeEnhancement3,
			// (double)persistVol,
			// (double)plateauVol,(double)washoutVol,(double)initialMediumEnhanceVol,(double)initialRapidEnhanceVol};

			// "StudyID,Name,MRN,Study Date,Measurement Date, KPACS filepath,Noise Level,PEthreshold, Mean PE, Mean SER,Peak PE,Peak SER,VOE2,VOE3,VOE4,"+
			// "InitialRapidEnhancement%,InitialMediumEnhancement%,CombinedPersistVol%,CombinedPlateauVol%,CombinedwashoutVol%"

			String line = studyID + "," + patientName + "," + patientID + ","
					+ studyDate + "," + returnDate("MM/dd/yy")
					+ ",KPACS fp placeholder," + noiseLevel + ","
					+ (100 * noiseLevel / maxPixel) + "," + 100*peThold + ","
					+ 100*results[0] + "," + results[1] + "," + results[2] + ","
					+ results[3] + "," + voxelVol*results[4] + "," +voxelVol* results[5] + ","
					+ voxelVol*results[6] + "," + results[7] + "," + results[8] + ","
					+ results[9] + "," + results[10] + "," + results[11];

			ps.println(line);
			ps.close();
		} catch (IOException e) {
			IJ.log("IOException " + e + " occurred");
		}
		IJ.log("Results written to " + resFile);
	}

	//Yifan added this
	public void saveIndividual(double[] results, ImagePlus ip0, String studyID,
			double noiseLevel, double maxPixel, double peThold) {

		String resFile_i = SAVEPATH_i;

		String header = ip0.getStack().getSliceLabel(1);
		String studyDate = header.substring(header.indexOf("Study Date") + 11,
				header.indexOf("0008,0021") - 1);
		String patientName = header.substring(
				header.indexOf("Patient's Name") + 15,
				header.indexOf("0010,0020") - 1);
		String patientID = header.substring(header.indexOf("Patient ID") + 11,header.indexOf("Patient ID") + 20);
		//String patientID = header.substring(header.indexOf("Patient ID") + 11,
		//		header.indexOf("0010,0021") - 1);

		String temp = "";

		String pixelSpacing = header.substring(header.indexOf("Pixel Spacing") +15, header.indexOf("Pixel Spacing") +22);
		//Yifan changed this for processing 4D HR
		if (header.contains("CAD~ REG 4D_HR")){
			pixelSpacing = header.substring(header.indexOf("Pixel Spacing") +15, header.indexOf("Pixel Spacing") +19);
		}
		double pixSpacing = Double.parseDouble(pixelSpacing);
		double sliceSpacing = 0.65;
		IJ.log("pixel spacing: "+pixSpacing);
		double voxelVol = (pixSpacing*pixSpacing*sliceSpacing)/1000; //cc's

		try {
			File myDir = new File(resFile_i);
			if (!myDir.exists()) {
				boolean worked = myDir.createNewFile(); // creates file, if it
														// doesn't exist
				if (!worked) {
					System.err
							.println("File " + myDir + "could not be created");
					return;
				}
				temp = "StudyID,Name,MRN,Study Date,Measurement Date, KPACS filepath,Noise Level,Noise Level as % of Max Pixel,PEthreshold, Mean PE, Mean SER,Peak PE,Peak SER,VOE2(cc),VOE3(cc),VOE4(cc),"
						+ "InitialRapidEnhancement%,InitialMediumEnhancement%,CombinedPersistVol%,CombinedPlateauVol%,CombinedwashoutVol%";
				temp += "break";
			} else {
				temp = "";
			}
			Scanner console = new Scanner(myDir);
			while (console.hasNextLine()) {
				temp += console.nextLine();
				temp += "break";
			}
			console.close();
			// myFile.createNewFile(); // If it doesn't exist, create it
			PrintStream ps = new PrintStream(new BufferedOutputStream(
					new FileOutputStream(myDir)));
			String[] temp1 = temp.split("break");
			for (int i = 0; i < temp1.length; i++) {
				ps.println(temp1[i]);
			}
			// results: double[] results = {meanPce, meanSer, peakPCE,
			// peakSER,(double)volumeEnhancement1,
			// (double)volumeEnhancement2,(double)volumeEnhancement3,
			// (double)persistVol,
			// (double)plateauVol,(double)washoutVol,(double)initialMediumEnhanceVol,(double)initialRapidEnhanceVol};

			// "StudyID,Name,MRN,Study Date,Measurement Date, KPACS filepath,Noise Level,PEthreshold, Mean PE, Mean SER,Peak PE,Peak SER,VOE2,VOE3,VOE4,"+
			// "InitialRapidEnhancement%,InitialMediumEnhancement%,CombinedPersistVol%,CombinedPlateauVol%,CombinedwashoutVol%"

			String line = studyID + "," + patientName + "," + patientID + ","
					+ studyDate + "," + returnDate("MM/dd/yy")
					+ ",KPACS fp placeholder," + noiseLevel + ","
					+ (100 * noiseLevel / maxPixel) + "," + 100*peThold + ","
					+ 100*results[0] + "," + results[1] + "," + results[2] + ","
					+ results[3] + "," + voxelVol*results[4] + "," +voxelVol* results[5] + ","
					+ voxelVol*results[6] + "," + results[7] + "," + results[8] + ","
					+ results[9] + "," + results[10] + "," + results[11];

			ps.println(line);
			ps.close();
		} catch (IOException e) {
			IJ.log("IOException " + e + " occurred");
		}
		IJ.log("Individual Result written to " + resFile_i);
	}

	public void save(String srcFilepath, ImagePlus[] plusSeries, ImagePlus serOverlay, double[] results, double peThreshold) {
		SAVEPATH_i = new File(srcFilepath).getParentFile().getPath() + File.separator + "Results";
		GenericDialog fpgd = new GenericDialog("Where to Save Individual result:");
		fpgd.addStringField("Where to Save Individual result: ", SAVEPATH_i);
		fpgd.setLayout(new BoxLayout(fpgd, BoxLayout.Y_AXIS));
		fpgd.setPreferredSize(new Dimension(600,150));
		fpgd.showDialog();

		SAVEPATH_i = fpgd.getNextString();
		fpgd.dispose();

		File saveFile = new File(SAVEPATH_i);
		if (!saveFile.exists()) {
			saveFile.mkdir();
		}
		//SAVEPATH = SAVEPATH.replaceAll("\\", "\\\");

		IJ.log(SAVEPATH_i);

		String studyID = IJ.getString("Study ID:", "");
		double maxPixel = plusSeries[0].getStatistics().max;

		saveMeasurements(results, plusSeries[0], studyID, NOISETHRESHOLD, maxPixel, peThreshold);
		//saveRawData(plusSeries, voi, studyID);
		saveColorMap(serOverlay, studyID);
	}

	// post: returns a Map<Integer, File> of the files with valid .dcm images in sorted order by timepoint
	// Warning: If the format of how results and/or scans are saved this method may need to be motified to ensure
	// the program is analyzing the correct images/scans
	public Map<Integer, File> getSeriesList(String filepath) {
		File srcFile = new File(filepath);
		java.io.FileFilter DCMFilter = new DCMFileFilter();
		File[] listOfSeries = srcFile.getParentFile().listFiles(DCMFilter);

		IJ.log("Folders " + listOfSeries.length);
		if (listOfSeries.length <= 1) {
			IJ.log("Folders <= 1");
		}
		//Yifan created this variable for adapting 2018 4D HR
		int timeSeriesNum_check = 0;
		Map<Integer, File> map = new TreeMap<Integer, File>();

		for (File file : listOfSeries) {
			if (file.isDirectory()) {
				File[] firstDCM = file.listFiles(DCMFilter);

				// !fistDCM[0].isDirectory() based on how results folder is set up
				if (firstDCM.length != 0 && !firstDCM[0].isDirectory()) {
					ImagePlus ip = new ImagePlus(firstDCM[0].getPath());

					String header = ip.getStack().getSliceLabel(1);
					int stInd = header.indexOf("Series Number");

					if (header.contains("CAD~ REG dyn eTHRIVE") || header.contains("CAD~ REG Ax Vibrant") ||
						header.contains("CAD~ REG 4D_HR") || header.contains("dyn eTHRIVE") || header.contains("THRIVE")) {
						
						int timeSeriesNum = Integer.parseInt(header.substring(stInd + 15, stInd + 19).replaceAll(" ", ""));
						//Yifan added this to "fake" the 3rd post if needed
						if (timeSeriesNum_check == timeSeriesNum){
							timeSeriesNum ++;
						} else timeSeriesNum_check = timeSeriesNum;

					//Yifan added this for non-Reg series
					if (timeSeriesNum < 9000){
							timeSeriesNum = Integer.parseInt(header.substring(stInd + 15, stInd + 23).replaceAll(" ", ""));
						}
						map.put(timeSeriesNum, file);
					}
				}
			}
		}
		//Yifan added this to process 4DHR series which does not have the 3rd post series

		return map;
	}

	/* post: opens the images in the File dir and sorts them by image number
	 * returns an ImagePlus object of the image stack with the stack label passed in
	*/
	public ImagePlus openImageStack(File dir, String stackLabel) {
		java.io.FileFilter DCMFilter = new DCMFileFilter();
		File[] listOfDicoms = dir.listFiles(DCMFilter);
		IJ.log("Number of DICOMS Found: " + listOfDicoms.length);

		// This is the number of images subtracted from the beginning and end of
		// the stack.
		// should be out of lesion range in most cases, but watch out for
		// exceptions.
		int cullNumber = 0;

		ImagePlus ip = new ImagePlus(listOfDicoms[0].getPath());
		ImageStack stk = new ImageStack(ip.getWidth(), ip.getHeight());
		int slices = listOfDicoms.length - (2 * cullNumber);
		//IJ.log("N of Slices: "+ slices);
		int[] listOfSliceNums = new int[slices];

		for (int i = cullNumber; i < listOfDicoms.length - (cullNumber); i++) {
			ip = new ImagePlus(listOfDicoms[i].getPath());
			//IJ.log(listOfDicoms[i].getPath());
			ip.trimProcessor();
			String header = ip.getStack().getSliceLabel(1);
			stk.addSlice(header, ip.getProcessor());
			int num = header.indexOf("Image Number:", header.indexOf("Image Number:") + 1);

			// get image number
			listOfSliceNums[i - cullNumber] = Integer.parseInt(header
					.substring(num + 14, num + 17).split("\\n")[0].replaceAll(" ", ""));
			// listOfSliceNums[i - cullNumber] = Integer.parseInt(header.replaceAll("{\\D]", "");
			IJ.showProgress(i, listOfDicoms.length - 2 * cullNumber);
		}

		// Sort by image number:
		for (int i = cullNumber; i < listOfDicoms.length - (cullNumber); i++) {

			for (int j = i + 1; j < listOfDicoms.length - (cullNumber + 1); j++) {
				if (listOfSliceNums[j - cullNumber] < listOfSliceNums[i - cullNumber]) {
					int num = listOfSliceNums[j - cullNumber];
					listOfSliceNums[j - cullNumber] = listOfSliceNums[i - cullNumber];
					listOfSliceNums[i - cullNumber] = num;

					ImageProcessor proi = stk.getProcessor(i + 1 - cullNumber);
					String h1 = stk.getSliceLabel(i + 1 - cullNumber);
					ImageProcessor proj = stk.getProcessor(j + 1 - cullNumber);
					String h2 = stk.getSliceLabel(j + 1 - cullNumber);

					stk.deleteSlice(i + 1 - cullNumber);
					stk.addSlice(h2, proj, i - cullNumber);
					stk.deleteSlice(j + 1 - cullNumber);
					stk.addSlice(h1, proi, j - cullNumber);

				}
			}
			IJ.showProgress(i, listOfDicoms.length - 1 - 2 * cullNumber);
		}

		ImagePlus finalIP = new ImagePlus(stackLabel, stk);
		return finalIP;
	}
	/* Constructs the axial maximum intensity projection (mip).
	 * Waits for the user to select a region of interest (ROI), and press spacebar.
	 * Constructs a saggital mip from the slices contained in the axial(x/y) ROI.
	 * Returns an array of bounding volume {x0,x1,y0,y1,z0,z1}.
	   8/7/12 replace voi with ROI object, get bounds.
	  */
	public int[] constructMIPS(ImageStack srcStk, ImageStack preStk) {
	//	ImageProcessor pro = srcStk.getProcessor(1);
	//	ImageProcessor prepro = srcStk.getProcessor(1);

		int height = srcStk.getHeight();
		int width = srcStk.getWidth();
		int nslices = srcStk.getSize();

		// float arrays to hold final MIP data.
		float[][] axmip = new float[width][height];
		float[][] sagmip = new float[height][2 * nslices];
		float[][] sagSlice = new float[height][2 * nslices];

		// construct axial MIP
		for (int n = 1; n <= nslices; n++) {
			ImageProcessor pro = srcStk.getProcessor(n);
			ImageProcessor prepro = preStk.getProcessor(n);
			for (int i = 0; i < width; i++) {
				for (int j = 0; j < height; j++) {
					// may want to replace ImagePlus.getPixelValue(x,y) with
					// ImageStack.getVoxel(x,y,z)
					float val = (float) pro.getPixelValue(i, j)			// subtract post constrast image from pre contrast
							- (float) prepro.getPixelValue(i, j);
					// > 0?, so if val is negative keep at 0?
					if (val > axmip[i][j]) {
						axmip[i][j] = val;
					}
				}
			}
		}

		// make stack of axial mip
		ImageStack axStack = new ImageStack(width, height);

		ImageProcessor axMipSlice1 = srcStk.getProcessor(1);

		// axial mip
		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				axMipSlice1.putPixelValue(i, j, (double) axmip[i][j]);
			}
		}

		axStack.addSlice(axMipSlice1);
		for (int i = 1; i <= nslices; i++) {
			axStack.addSlice(srcStk.getProcessor(i));
		}


		ImagePlus axStackIp = new ImagePlus("Select XY Roi", axStack);
		axStackIp.show();		// opens the image

		// gets current image being shown and the roi
		Roi xyroi = IJ.getImage().getRoi();

		// retrieve x/y roi (region of interest)
		while (!IJ.spaceBarDown() || xyroi == null) {
			xyroi = axStackIp.getRoi();
			IJ.wait(5);
		}

		globalXYroi = xyroi;
		Rectangle xyroiBnds = xyroi.getBounds();

		float pix;

		// Construct saggital mip
		for (int n = 0; n < 2 * nslices; n += 2) {
			ImageProcessor pro = srcStk.getProcessor((n / 2) + 1);
			ImageProcessor prepro = preStk.getProcessor((n / 2) + 1);
			for (int j = 0; j < height; j++) {
				for (int i = xyroiBnds.x; i < (xyroiBnds.x + xyroiBnds.width); i++) {
					pix = (float) pro.getPixelValue(i, j)
							- (float) prepro.getPixelValue(i, j);
					if (sagmip[j][n] < pix) {
						sagmip[j][n] = pix;
						sagmip[j][n + 1] = sagmip[j][n];		// isnt this putting 0 in the next slot which is already 0
						// sagmip[j][n+2] = sagmip[j][n];
					}
				}
			}
		}

		FloatProcessor fp = new FloatProcessor(sagmip);
		fp.flipVertical();
		fp.setMinAndMax(0, fp.getStatistics().max);
		// add sagittal stack
		ImageStack sagStk = new ImageStack(height, 2 * nslices);
		sagStk.addSlice(fp);

		for (int i = 0; i < xyroiBnds.width; i++) {
			for (int n = 0; n < 2 * nslices; n += 2) {
				ImageProcessor pro = srcStk.getProcessor((n / 2) + 1);
				for (int j = 0; j < height; j++) {
					sagSlice[j][n] = (float) pro.getPixelValue(i + xyroiBnds.x, j);
					sagSlice[j][n + 1] = sagSlice[j][n];
				}
			}
			FloatProcessor newSlice = new FloatProcessor(sagSlice);
			newSlice.flipVertical();
			newSlice.setMinAndMax(0, newSlice.getStatistics().max);
			newSlice.setColor(300);
			newSlice.drawLine(xyroiBnds.y - 1, 1, xyroiBnds.y - 1,
					newSlice.getHeight());
			newSlice.drawLine(xyroiBnds.y + xyroiBnds.height + 1, 1,
					xyroiBnds.y + xyroiBnds.height + 1, newSlice.getHeight());
			sagStk.addSlice(newSlice);
		}

		// ImagePlus sagresult = new ImagePlus("sagMIP",fp);
		ImagePlus sagresult = new ImagePlus("sagMIP", sagStk);
		// draw vertical lines to show where box was drawn on axial mip
		sagresult.getProcessor().setColor(300);
		sagresult.getProcessor().drawLine(xyroiBnds.y - 1, 1, xyroiBnds.y - 1,
				sagresult.getHeight());
		sagresult.getProcessor().drawLine(xyroiBnds.y + xyroiBnds.height + 1,
				1, xyroiBnds.y + xyroiBnds.height + 1, sagresult.getHeight());
		sagresult.show();

		// should be yz roi. Naming inconsistency. Set globalYZroi.
		Roi xzroi = IJ.getImage().getRoi();

		// retrieve x/z roi
		while (!IJ.spaceBarDown() || xzroi == null) {
			xzroi = IJ.getImage().getRoi();
			IJ.wait(5);
		}

		globalYZroi = xzroi;
		Rectangle xzroiBnds = xzroi.getBounds();


		int z0 = (int) (xzroiBnds.y / 2);
		int z1 = (int) ((xzroiBnds.y + xzroiBnds.height) / 2);


		axStack.deleteLastSlice();

		axStackIp.close();
		sagresult.close();

		int[] returnArr = { xyroiBnds.x, (xyroiBnds.x + xyroiBnds.width),
				xyroiBnds.y, (xyroiBnds.y + xyroiBnds.height), z0, z1 };
		return returnArr;

	}

	/*	post: selects the noise threshold for the image
	 * 	parameters:
	 * 		ImagePlus[] plusSeries: the series of images stored based on their timepoint.
	 * 		 	PreContrast plusSeries[0], then the three post
	 * 		int[] voi: bounds of the volume of interest
	 * 			{xyroiBnds.x, (xyroiBnds.x + xyroiBnds.width), xyroiBnds.y, (xyroiBnds.y + xyroiBnds.height), z0, z1}
	 */
	public void selectNoiseThreshold(ImagePlus[] plusSeries, int[] voi) {
		double maxPixel = plusSeries[0].getStatistics().max;
		ImageStack selectorStk = new ImageStack(plusSeries[0].getWidth(), plusSeries[0].getHeight());
		ImageStack preconStk = plusSeries[0].getStack();

		IJ.log("slice range = " + voi[4] + "," + voi[5]);

		for (int i = voi[4]; i <= voi[5]; i++) {
			ImageProcessor newPro = preconStk.getProcessor(i).duplicate();
			newPro.setMinAndMax(0, newPro.getStatistics().max);
			newPro.setColor(100000);
			newPro.drawRect(voi[0], voi[2], voi[1] - voi[0], voi[3] - voi[2]);
			selectorStk.addSlice(newPro);
		}

		ImagePlus noiseSelectorIP = new ImagePlus("Pre-Contrast", selectorStk);
		noiseSelectorIP.show();

		ImagePlus.addImageListener(this);

		GenericDialog gd = new NonBlockingGenericDialog("Noise Threshold");
		gd.setName("noiseDialog");
		gd.addSlider("TH", 0, maxPixel, 0);
		gd.setLocation(1, 1);
		gd.addDialogListener(this);
		gd.showDialog();

		double noiseLevel = NOISETHRESHOLD;
		gd.dispose();
		ImagePlus.removeImageListener(this);
		noiseSelectorIP.close();
		IJ.log("noise level = " + noiseLevel);
	}

	public void selectNoiseThreshold2D(ImagePlus ip) {
		ImagePlus.addImageListener(this);

		GenericDialog gd = new NonBlockingGenericDialog("Noise Threshold");
		gd.setName("noiseDialog");
		gd.addSlider("TH", 0, ip.getStatistics().max, 0);
		gd.setLocation(1, 1);
		gd.addDialogListener(this);
		gd.showDialog();

		double noiseLevel = NOISETHRESHOLD;
		gd.dispose();
		ImagePlus.removeImageListener(this);
		//ip.close();
		IJ.log("noise level = " + noiseLevel);
	}

	// post: updates the view when setting the threshold.

	// Sections to be thrown out are colored red
	public void updateMask() {
		// get DWI processor to calc mask.
		ImagePlus ip = IJ.getImage();
		ImageStack stk = ip.getStack();
		int curSlice = ip.getCurrentSlice();
		int thold = NOISETHRESHOLD;

		ImageProcessor src4Mask = stk.getProcessor(curSlice);
		ColorProcessor newMask = src4Mask.convertToColorProcessor();

		int width = ip.getWidth();
		int height = ip.getHeight();

		// set mask
		// double val;
		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				if (src4Mask.getPixelValue(i, j) < thold) {
					newMask.putPixel(i, j, new int[] {255, 0, 0});
				}
			}
		}

		ImageRoi Imroi = new ImageRoi(0, 0, newMask);
		Imroi.setOpacity(0.5);
		ip.setOverlay(new Overlay(Imroi));
	}



	/* original: update threshold mask.
	public void updateMask2() {
		// get DWI processor to calc mask.
		ImagePlus ip = IJ.getImage();
		ImageStack stk = ip.getStack();
		int curSlice = ip.getCurrentSlice();
		int thold = NOISETHRESHOLD;

		ImageProcessor src4Mask = stk.getProcessor(curSlice);
		ImageProcessor newMask = src4Mask.duplicate();

		int width = ip.getWidth();
		int height = ip.getHeight();

		// set mask
		// double val;
		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				if (src4Mask.getPixelValue(i, j) < thold) {
					newMask.putPixelValue(i, j, 50);

				}
			}
		}
		ImageRoi Imroi = new ImageRoi(0, 0, newMask);
		Imroi.setOpacity(0.5);

		ip.setOverlay(new Overlay(Imroi));

	} */


	public void measurements2D(ImagePlus[] plusSeries, Rectangle bounds, ImagePlus source, double peThreshold, String srcFilepath) {
		int numSeries = source.getStackSize();
		ImageStack stack =  source.getStack();


		double[][] pceField = new double[bounds.width + 1]
										  [bounds.height + 1];


		double[][] serField = new double[bounds.width + 1]
										[bounds.height + 1];

		// defines which pixels within VOI and meet initial enhancement criteria
		boolean[][] peMaskField = new boolean[bounds.width + 1]
				  							 [bounds.height + 1];
		int xabs = 0, yabs = 0;
		double meanPce = 0, meanSer = 0;
		int nn = 1;
		double[] pixels = new double[numSeries];
		// volume of enhancement at each timepoint, defined as the volume with
		// an enhancement
		// greater than the threshold. integrated density = sum of signal over
		// same volume.
		int volumeEnhancement1 = 0, volumeEnhancement2 = 0, volumeEnhancement3 = 0;

		int washoutVol = 0, persistVol = 0, plateauVol = 0, initialRapidEnhanceVol = 0, initialMediumEnhanceVol = 0;


		// evaluate slice by slice
		ImageProcessor[] pros = new ImageProcessor[numSeries];

		for (int i = 1; i <= numSeries; i++) {
			pros[i - 1] = stack.getProcessor(i);
		}
			for (int x = bounds.x; x <= bounds.width + bounds.x; x++) {
				xabs = x - bounds.x;
				for (int y = bounds.y; y <= bounds.height + bounds.y; y++) {
					yabs = y - bounds.y;

					if (globalXYroi.contains(x, y)) {
						for (int i = 0; i < numSeries; i++) {
							pixels[i] = (double) (pros[i].getPixelValue(x, y));
						}

						if (pixels[1] / pixels[0] > peThreshold && pixels[0] > NOISETHRESHOLD) {
							peMaskField[xabs][yabs] = true;
							volumeEnhancement1++;
						//	intDensity1 += pixels[1];

							if (pixels[1] / pixels[0] > 1.5 && pixels[1] / pixels[0] <= 2.0) {
								initialMediumEnhanceVol++; // increment initial medium enhancement volume
							}
							if (pixels[1] / pixels[0] > 2.0) {
								initialRapidEnhanceVol++; // increment initial rapid enhancement volume
							}

							double serVal = (pixels[1] - pixels[0])
									/ (pixels[numSeries - 1] - pixels[0]);

							if (serVal > 1.1 && serVal <3) {
								washoutVol++; // increment washoutVol
							}

							if (serVal < 0.9) {
								persistVol++; // increment persistence vol
							}

							if (serVal >= 0.9 && serVal <= 1.1) {
								plateauVol++; // increment plateau vol
							}
							meanPce = ((meanPce * (nn - 1)) + (pixels[1] - pixels[0])
									/ pixels[0])
									/ nn;
							if ((pixels[numSeries - 1] - pixels[0]) != 0 && serVal <3) {
								meanSer = ((meanSer * (nn - 1)) + serVal) / nn; // running
																				// mean
																				// of
																				// SER
							}
							nn++;
							// get pixel surface area.
							// calculate percent enhancement.
							pceField[xabs][yabs] = 100 * ((pixels[1] - pixels[0]) / pixels[0]);
							// calculation of SER
							serField[xabs][yabs] = serVal;

							if (serField[xabs][yabs] > 3) {
								serField[xabs][yabs] = 0;
								pceField[xabs][yabs] = 0;
							}

						} else {
							peMaskField[xabs][yabs] = false;
						}

						if (pixels[2] / pixels[0] > peThreshold && pixels[0] > NOISETHRESHOLD) {
							volumeEnhancement2++;
							//intDensity2 += pixels[2];
						}
						if (numSeries > 3) {
							if (pixels[3] / pixels[0] > peThreshold && pixels[0] > NOISETHRESHOLD) {
								volumeEnhancement3++;
								//intDensity3 += pixels[3];
							}
						}
					// if ROI contains
					}
			}
		}// end stack loop.

			int[] voi = {bounds.x, (bounds.x + bounds.width), bounds.y, (bounds.y + bounds.height)};
		// get peak enhancement and peak SER spots based on criteria.
		ImagePlus ip = source.duplicate();		// ensures the drawn roi does not appear on the saved ser overlay
		double peakPCE = getPeakSpot2D(ip, voi, pceField);
		double peakSER = getPeakSpot2D(ip, voi, serField);
		ip.close();

	//	source.close();
		// Now measure relevant params
		ImagePlus serOverlay = makeColorMap2D(source, "SER", serField, pceField, bounds);
		serOverlay.show();
		//mean PE, mean SER, peak PE, peak SER, voe2, voe3, voe4

		double[] results = { meanPce, meanSer, peakPCE, peakSER,
				(double) volumeEnhancement1, (double) volumeEnhancement2,
				(double) volumeEnhancement3,
				(double) initialRapidEnhanceVol / (double) volumeEnhancement1,
				(double) initialMediumEnhanceVol / (double) volumeEnhancement1,
				(double) persistVol / (double) volumeEnhancement1,
				(double) plateauVol / (double) volumeEnhancement1,
				(double) washoutVol / (double) volumeEnhancement1 };

		//save measures
		save(srcFilepath, plusSeries, serOverlay, results, peThreshold);
	//	serOverlay.close();
	}

	/* post: measures and saves the volume enhancement, washout volume, persistent volume, plateau volume, meanPce, meanSer, peakPCE, peakSER,
	 * and overlays then saves a color map on the image based on its enhancement
	 * parameters:
	 * 	ImagePlus[] plusSeries: MRI images with indices corresponding to their timepoint
	 * 	int[] voi = {xyroiBnds.x, (xyroiBnds.x + xyroiBnds.width), xyroiBnds.y, (xyroiBnds.y + xyroiBnds.height), z0, z1}
	 * 	double enhancementThold: image enhancement threshold
	 * 	double noiseThreshold: image noise threshold
	 */
	public void measureDCEparams(ImagePlus[] plusSeries, int[] voi, String srcFilepath, double peThreshold) {
		int numSeries = plusSeries.length;

		// flip voi on z axis (coordinate transformation).
		int width = voi[5] - voi[4];
		int zDepth = plusSeries[0].getNSlices();
	//	int yDepth = plusSeries[0].getHeight();
	//	int xDepth = plusSeries[0].getWidth();
		voi[4] = zDepth - (voi[5] - 1);
		voi[5] = voi[4] + width;
		voiGlobal = voi;

		// Remember this ROI is upside down and stretched x2 in the z axis relative to the data.
		// To use it we need to identify if a slice is in the ROI, and if a
		// pixel is within the ROI in the y axis.
//		Roi yzRoi = globalYZroi;

		// Make new ImagePlus containing only the slices in VOI.


		ImageStack[] stacks = new ImageStack[numSeries];

		for (int i = 0; i < numSeries; i++) {
			stacks[i] = plusSeries[i].getStack();
		}

		double[][][] pceField = new double[voi[1] - voi[0] + 1]
										  [voi[3] - voi[2] + 1]
										  [voi[5] - voi[4] + 1];

		double[][][] serField = new double[voi[1] - voi[0] + 1]
										  [voi[3] - voi[2] + 1]
										  [voi[5] - voi[4] + 1];

		// defines which pixels within VOI and meet initial enhancement criteria
		boolean[][][] peMaskField = new boolean[voi[1] - voi[0] + 1]
											   [voi[3] - voi[2] + 1]
											   [voi[5] - voi[4] + 1];
		int xabs = 0, yabs = 0, zabs = 0;
		double meanPce = 0, meanSer = 0;
		int nn = 1;
		double[] pixels = new double[numSeries];
		// volume of enhancement at each timepoint, defined as the volume with
		// an enhancement
		// greater than the threshold. integrated density = sum of signal over
		// same volume.
		int volumeEnhancement1 = 0, volumeEnhancement2 = 0, volumeEnhancement3 = 0;

		int washoutVol = 0, persistVol = 0, plateauVol = 0, initialRapidEnhanceVol = 0, initialMediumEnhanceVol = 0;
		//int surfaceArea1 = 0, surfaceArea2 = 0, surfaceArea3 = 0;
	//	int intDensity1 = 0, intDensity2 = 0, intDensity3 = 0;
		// ImageStatistics stats = plusSeries[0].getStatistics();

		/*
		 * double noiseLevel = 0;
		 *
		 * double thisPixel; ImageProcessor thisPro; //get max within VOI
		 * for(int z=voi[4];z<=voi[5];z++){ thisPro = stacks[0].getProcessor(z);
		 * for(int y=voi[2];y<=voi[3];y++){ for(int x=voi[0];x<=voi[1];x++){
		 * thisPixel = thisPro.getPixelValue(x, y); if(noiseLevel < thisPixel){
		 * noiseLevel = thisPixel; } } } }
		 *
		 * //Convert to % of maximum. noiseLevel = noiseLevel*noiseThreshold;
		 */

//		IJ.log("noise level = " + noiseLevel);

		// evaluate slice by slice
		for (int z = voi[4]; z <= voi[5]; z++) {
			zabs = z - voi[4];
			ImageProcessor[] pros = new ImageProcessor[numSeries];

			for (int i = 0; i < numSeries; i++) {
				pros[i] = stacks[i].getProcessor(z);
			}

			for (int y = voi[2]; y <= voi[3]; y++) {
				yabs = y - voi[2];
				for (int x = voi[0]; x <= voi[1]; x++) {
					xabs = x - voi[0];

					if (globalXYroi.contains(x, y)) {

						for (int i = 0; i < numSeries; i++) {
							pixels[i] = (double) (pros[i].getPixelValue(x, y));
						}

						if (pixels[1] / pixels[0] > peThreshold && pixels[0] > NOISETHRESHOLD) {
							peMaskField[xabs][yabs][zabs] = true;
							volumeEnhancement1++;
						//	intDensity1 += pixels[1];

							if (pixels[1] / pixels[0] > 1.5 && pixels[1] / pixels[0] <= 2.0) {
								initialMediumEnhanceVol++; // increment initial medium enhancement volume
							}
							if (pixels[1] / pixels[0] > 2.0) {
								initialRapidEnhanceVol++; // increment initial rapid enhancement volume
							}

							double serVal = (pixels[1] - pixels[0])
									/ (pixels[numSeries - 1] - pixels[0]);

							if (serVal > 1.1 && serVal <3) {
								washoutVol++; // increment washoutVol
							}

							if (serVal < 0.9) {
								persistVol++; // increment persistence vol
							}

							if (serVal >= 0.9 && serVal <= 1.1) {
								plateauVol++; // increment plateau vol
							}
							meanPce = ((meanPce * (nn - 1)) + (pixels[1] - pixels[0])
									/ pixels[0])
									/ nn;
							if ((pixels[numSeries - 1] - pixels[0]) != 0 && serVal <3) {
								meanSer = ((meanSer * (nn - 1)) + serVal) / nn; // running
																				// mean
																				// of
																				// SER
							}
							nn++;
							// get pixel surface area.
							// calculate percent enhancement.
							pceField[xabs][yabs][zabs] = 100 * ((pixels[1] - pixels[0]) / pixels[0]);
							// calculation of SER
							serField[xabs][yabs][zabs] = serVal;

							if (serField[xabs][yabs][zabs] > 3) {
								serField[xabs][yabs][zabs] = 0;
								pceField[xabs][yabs][zabs] = 0;
							}

						} else {
							peMaskField[xabs][yabs][zabs] = false;
						}

						if (pixels[2] / pixels[0] > peThreshold && pixels[0] > NOISETHRESHOLD) {
							volumeEnhancement2++;
							//intDensity2 += pixels[2];
						}
						if (numSeries > 3) {
							if (pixels[3] / pixels[0] > peThreshold && pixels[0] > NOISETHRESHOLD) {
								volumeEnhancement3++;
								//intDensity3 += pixels[3];
							}
						}
					}// if ROI contains
				}
			}
		}// end stack loop.

		// get peak enhancement and peak SER spots based on criteria.
		ImagePlus ip = plusSeries[1].duplicate();		// ensures the drawn roi does not appear on the saved ser overlay
		double peakPCE = getPeakSpot(ip, voi, pceField);
		double peakSER = getPeakSpot(ip, voi, serField);
		ip.close();

		// Now measure relevant params
		ImagePlus serOverlay = makeColorMap(plusSeries[1], "SER", serField,
				pceField, peMaskField, voi);
		serOverlay.show();
		//mean PE, mean SER, peak PE, peak SER, voe2, voe3, voe4

		double[] results = { meanPce, meanSer, peakPCE, peakSER,
				(double) volumeEnhancement1, (double) volumeEnhancement2,
				(double) volumeEnhancement3,
				(double) initialRapidEnhanceVol / (double) volumeEnhancement1,
				(double) initialMediumEnhanceVol / (double) volumeEnhancement1,
				(double) persistVol / (double) volumeEnhancement1,
				(double) plateauVol / (double) volumeEnhancement1,
				(double) washoutVol / (double) volumeEnhancement1 };

		//save measures
		save(srcFilepath, plusSeries, serOverlay, results, peThreshold);
	}

	public ImagePlus makeColorMap2D(ImagePlus source, String peOrSER, double[][] serField, double[][] pefield, Rectangle bounds) {
//		ImageStack colorOverlayStack = new ImageStack(sourceIP.getWidth(), sourceIP.getHeight());
//		ImageStack sourceStk = sourceIP.getStack();
		ImageProcessor srcSlice = source.getProcessor();
		ColorProcessor newSlice = srcSlice.convertToColorProcessor();

		// add slices above VOI
		// for(int i=1;i<=voi[4];i++){
		//colorOverlayStack.addSlice((ColorProcessor)sourceStk.getProcessor(i).convertToRGB());
		// }

//		int xabs = 0, yabs = 0, zabs = 0;
//		// add slices in VOI
//		for (int z = voi[4]; z <= voi[5]; z++) {
//			zabs = z - voi[4];
//			srcSlice = sourceStk.getProcessor(z).duplicate();
//
//			// newSlice = new ColorProcessor(srcSlice.getWidth(),
//			// srcSlice.getHeight());
//			newSlice = (ColorProcessor) srcSlice.convertToRGB();

			for (int x = bounds.x; x < bounds.width + bounds.x; x++) {
				int xabs = x - bounds.x;
				for (int y = bounds.y; y < bounds.height + bounds.y; y++) {
					int yabs = y - bounds.y;
					if (source.getPixel(x, y)[0] > NOISETHRESHOLD && globalXYroi.contains(x, y)) {
						//newSlice.putPixel(x, y, new int[] { 0, 255, 0 });
						if (peOrSER == "SER") {
							if (serField[xabs][yabs] < 0.9) {
								newSlice.putPixel(x, y, new int[] { 0, 0, 255 });
							} else if (serField[xabs][yabs] > 1.1) {
								newSlice.putPixel(x, y, new int[] { 255, 0, 0 });
							} else {
								newSlice.putPixel(x, y, new int[] { 0, 255, 0 });
							}
						} else if (peOrSER == "PE") {

						}
					}
				}
			}
			newSlice.resetRoi();

		return new ImagePlus("Color Overlay", newSlice);
	}
	// Constructs RGB color map overlaid onto axial image. String peOrSer ==
	// "PE" for pe map; "SER" for ser map
	public ImagePlus makeColorMap(ImagePlus sourceIP, String peOrSer,
			double[][][] serField, double[][][] peField,
			boolean[][][] maskField, int[] voi) {

		ImageStack colorOverlayStack = new ImageStack(sourceIP.getWidth(), sourceIP.getHeight());
		ImageStack sourceStk = sourceIP.getStack();
		ImageProcessor srcSlice;
		ColorProcessor newSlice;

		// add slices above VOI
		// for(int i=1;i<=voi[4];i++){
		//colorOverlayStack.addSlice((ColorProcessor)sourceStk.getProcessor(i).convertToRGB());
		// }

		int xabs = 0, yabs = 0, zabs = 0;
		// add slices in VOI
		for (int z = voi[4]; z <= voi[5]; z++) {
			zabs = z - voi[4];
			srcSlice = sourceStk.getProcessor(z).duplicate();

			// newSlice = new ColorProcessor(srcSlice.getWidth(),
			// srcSlice.getHeight());
			newSlice = (ColorProcessor) srcSlice.convertToRGB();

			for (int x = voi[0]; x <= voi[1]; x++) {
				xabs = x - voi[0];
				for (int y = voi[2]; y <= voi[3]; y++) {
					yabs = y - voi[2];
					if (maskField[xabs][yabs][zabs] && globalXYroi.contains(x, y)) {

						if (peOrSer == "SER") {
							if (serField[xabs][yabs][zabs] < 0.9) {
								newSlice.putPixel(x, y, new int[] { 0, 0, 255 });
							} else if (serField[xabs][yabs][zabs] > 1.1) {
								newSlice.putPixel(x, y, new int[] { 255, 0, 0 });
							} else {
								newSlice.putPixel(x, y, new int[] { 0, 255, 0 });
							}
						} else if (peOrSer == "PE") {

						}
					}
				}
			}
			newSlice.resetRoi();
			colorOverlayStack.addSlice(newSlice);
		}


		return new ImagePlus("SER overlay", colorOverlayStack);
	}

	public void drawSpot(ImagePlus imp, int x, int y) {
	//	GeneralPath path = new GeneralPath();
		float r = 2;
	//	path.append(new Ellipse2D.Float(x, y, r, r), false);
	//	imp.setOverlay(path, Color.green, null);
//		imp.killRoi();
		imp.setOverlay(new Roi(x, y, r, r, 10), Color.green, 2, Color.green);
	}

	/*
	 * public void updateMIP(int centerSlice, int slabThickness){
	 *
	 * //if(!repeatCallback){ // return; //} repeatCallback = false; //ImagePlus
	 * srcStk = p1Global; ImageStack p1stk = srcStk.getStack(); ImageProcessor
	 * pro = p1stk.getProcessor(1); IJ.selectWindow("axMIP"); ImagePlus mipIP =
	 * IJ.getImage();
	 *
	 * int height = p1stk.getHeight(); int width = p1stk.getWidth(); int nslices
	 * = srcStk.getNSlices();
	 *
	 * int mipRangeLower = centerSlice - (int)(slabThickness/2); int
	 * mipRangeUpper = centerSlice + (int)(slabThickness/2);
	 *
	 * if( mipRangeLower < 1){ mipRangeLower=1; } if(mipRangeUpper > nslices){
	 * mipRangeUpper = nslices; }
	 *
	 * //float arrays to hold final MIP data. float[][]axmip = new
	 * float[width][height];
	 *
	 * //Initialize arrays to zeros. for(int i=0;i<width;i++){ for(int
	 * j=0;j<height;j++){ axmip[i][j]= 0; } }
	 *
	 * //construct MIP for(int n = mipRangeLower;n<=mipRangeUpper;n++){ pro =
	 * p1stk.getProcessor(n); for(int i=0;i<width;i++){ for(int
	 * j=0;j<height;j++){
	 *
	 * //may want to replace ImagePlus.getPixelValue(x,y) with
	 * ImageStack.getVoxel(x,y,z) float val = pro.getPixelValue(i,j);
	 * if(val>axmip[i][j]){ axmip[i][j]= val; } } } }
	 *
	 * FloatProcessor fp = new FloatProcessor(axmip); //fp.flipVertical();
	 * //ImagePlus axresult = new ImagePlus("axMIP",fp); mipIP = new
	 * ImagePlus("axMIP",fp); mipIP.updateAndDraw(); repeatCallback = true;
	 *
	 * }
	 */
	// Get peak spot in scalar field

//	voi[4] voi[5]
	// 	int[] voi = {xyroiBnds.x, (xyroiBnds.x + xyroiBnds.width), xyroiBnds.y, (xyroiBnds.y + xyroiBnds.height), z0, z1}
	public double getPeakSpot2D(ImagePlus ip, int[] voi, double[][] field) {
		double val = 0;
		double t1, t2, t3;
		int nTopSpots = 400;

		topSpotsList = new double[nTopSpots][3];

	/*	for (int i = 0; i < nTopSpots; i++) {
			for (int j = 0; j < 4; j++) {
				topSpotsList[i][j] = -1;
			}
		}
*/
		// width - x, height - y
		for (int x = 1; x < (voi[1] - voi[0]); x++) {
			for (int y = 1; y < (voi[3] - voi[2]); y++) {


					// average over 3x3 voxel
					val = 0;
					for (int i = 0; i <= 1; i++) {
						for (int j = 0; j <= 1; j++) {

								val += field[x + i][y + j];

						}
					}
					val = val / 9.0;

					// if val is larger than lowest in list, insert then sort.
					if (val > topSpotsList[nTopSpots - 1][2]) {
					//	topSpotsList[nTopSpots - 1][3] = val;
					//	topSpotsList[nTopSpots - 1][2] = z + voi[4];
						topSpotsList[nTopSpots - 1][2] = val;
						topSpotsList[nTopSpots - 1][1] = y + voi[2];
						topSpotsList[nTopSpots - 1][0] = x + voi[0];

						for (int nn = 0; nn < nTopSpots - 1; nn++) {
							for (int mm = nn + 1; mm < nTopSpots; mm++) {
								if (topSpotsList[mm][2] > topSpotsList[nn][2]) {
									t1 = topSpotsList[nn][0];
									t2 = topSpotsList[nn][1];
									t3 = topSpotsList[nn][2];
								//	t4 = topSpotsList[nn][3];

									topSpotsList[nn][0] = topSpotsList[mm][0];
									topSpotsList[nn][1] = topSpotsList[mm][1];
									topSpotsList[nn][2] = topSpotsList[mm][2];
								//	topSpotsList[nn][3] = topSpotsList[mm][3];

									topSpotsList[mm][0] = t1;
									topSpotsList[mm][1] = t2;
									topSpotsList[mm][2] = t3;
								//	topSpotsList[mm][3] = t4;
								}
							}
						}
					}

			}
		}// end voxel search loop.
		// add slices with field information
		ImageStack peakStk = new ImageStack(ip.getWidth(), ip.getHeight());
		ImageStack srcStk = ip.getStack();
		//FloatProcessor pro;

			FloatProcessor pro = new FloatProcessor(srcStk.getWidth(), srcStk.getHeight());
			// set field
			for (int x = 0; x < (voi[1] - voi[0]); x++) {
				for (int y = 0; y < (voi[3] - voi[2]); y++) {
					pro.putPixelValue(x + voi[0], y + voi[2],
							(float) field[x][y]);
				}
			}
			pro.setMinAndMax(0, pro.getStatistics().max);
			peakStk.addSlice(pro);


		ImagePlus post1AndField = new ImagePlus("Field Stack", peakStk);


		//open image stack.
		post1AndField.show();

		post1AndField.getWindow().setLocation(-200, 0);
		ip.setTitle("Post1 Stack");

		ip.show();

		numChosen = -1;

		NonBlockingGenericDialog nbd = new NonBlockingGenericDialog(
				"Choose Peak");
		nbd.setName("peakPEDialog2D");
		nbd.addSlider("Choose point", 1, nTopSpots, 1);
		nbd.addDialogListener(this);
		nbd.showDialog();
		val = -1;

		if (nbd.wasOKed()) {
			val = numChosen;
			post1AndField.close();
		}

		return topSpotsList[numChosen][2];
	}

	/* post: gets the peak enhancement spot for image
	 * Parameters:
	 * 	ImagePlus ip: the image stack being considered
	 * 	int[] voi: bounds of the volume of interest
	 * 		{xyroiBnds.x, (xyroiBnds.x + xyroiBnds.width), xyroiBnds.y, (xyroiBnds.y + xyroiBnds.height), z0, z1}
	 * 	double[][][] field: calculated field values
	 * 		{ [voi[1] - voi[0] + 1][voi[3] - voi[2] + 1][voi[5] - voi[4] + 1] }
	 */
	public double getPeakSpot(ImagePlus ip, int[] voi, double[][][] field) {
		double val = 0;
		double t1, t2, t3, t4;
		int nTopSpots = 400;

		topSpotsList = new double[nTopSpots][4];

	/*	for (int i = 0; i < nTopSpots; i++) {
			for (int j = 0; j < 4; j++) {
				topSpotsList[i][j] = -1;
			}
		}
*/
		for (int x = 1; x < (voi[1] - voi[0]); x++) {
			for (int y = 1; y < (voi[3] - voi[2]); y++) {
				for (int z = 1; z < (voi[5] - voi[4]); z++) {

					// average over 3x3 voxel
					val = 0;
					for (int i = -1; i <= 1; i++) {
						for (int j = -1; j <= 1; j++) {
							for (int k = -1; k <= 1; k++) {
								val += field[x + i][y + j][z + k];
							}
						}
					}
					val = val / 27.0;
					// if val is larger than lowest in list, insert then sort.
					if (val > topSpotsList[nTopSpots - 1][3]
							&& globalXYroi.contains(x + voi[0] - 1, y + voi[2]
									- 1)) {
						topSpotsList[nTopSpots - 1][3] = val;
						topSpotsList[nTopSpots - 1][2] = z + voi[4];
						topSpotsList[nTopSpots - 1][1] = y + voi[2];
						topSpotsList[nTopSpots - 1][0] = x + voi[0];

						for (int nn = 0; nn < nTopSpots - 1; nn++) {
							for (int mm = nn + 1; mm < nTopSpots; mm++) {
								if (topSpotsList[mm][3] > topSpotsList[nn][3]) {
									t1 = topSpotsList[nn][0];
									t2 = topSpotsList[nn][1];
									t3 = topSpotsList[nn][2];
									t4 = topSpotsList[nn][3];

									topSpotsList[nn][0] = topSpotsList[mm][0];
									topSpotsList[nn][1] = topSpotsList[mm][1];
									topSpotsList[nn][2] = topSpotsList[mm][2];
									topSpotsList[nn][3] = topSpotsList[mm][3];

									topSpotsList[mm][0] = t1;
									topSpotsList[mm][1] = t2;
									topSpotsList[mm][2] = t3;
									topSpotsList[mm][3] = t4;
								}
							}
						}
					}
				}
			}
		}// end voxel search loop.

		// add slices with field information
		ImageStack peakStk = new ImageStack(ip.getWidth(), ip.getHeight());
		ImageStack srcStk = ip.getStack();
		//FloatProcessor pro;
		for (int z = voi[4]; z < voi[5]; z++) {
			FloatProcessor pro = new FloatProcessor(srcStk.getWidth(), srcStk.getHeight());
			// set field
			for (int x = 0; x < (voi[1] - voi[0]); x++) {
				for (int y = 0; y < (voi[3] - voi[2]); y++) {
					pro.putPixelValue(x + voi[0], y + voi[2],
							(float) field[x][y][z - voi[4]]);
				}
			}
			pro.setMinAndMax(0, pro.getStatistics().max);
			peakStk.addSlice(pro);
		}

		ImagePlus post1AndField = new ImagePlus("Field Stack", peakStk);


		//open image stack.
		post1AndField.show();

		post1AndField.getWindow().setLocation(-200, 0);
		ip.setTitle("Post1 Stack");

		ip.show();

		numChosen = -1;

		NonBlockingGenericDialog nbd = new NonBlockingGenericDialog(
				"Choose Peak");
		nbd.setName("peakPEDialog");
		nbd.addSlider("Choose point", 1, nTopSpots, 1);
		nbd.addDialogListener(this);
		nbd.showDialog();
		val = -1;

		if (nbd.wasOKed()) {
			val = numChosen;
			post1AndField.close();
		}

		return topSpotsList[numChosen][3];
	}

	/* post: saves the results to the specified filepath
	   if data already exist in that file print it along with the new results
	   if a file could not be created at SAVEPATH, throws an IOException and/or prints an error message

		parameters:
			double[] results = { meanPce, meanSer, peakPCE, peakSER, (double) volumeEnhancement1, (double) volumeEnhancement2,
								(double)volumeEnhancement3, (double)persistVol, (double)plateauVol, (double) washoutVol,
		                    	(double) initialMediumEnhanceVol,(double)initialRapidEnhanceVol };
			ImagePlus ip0: initial (pre-contrast) stack of images
*/

	/*
	public void saveMeasurements(double[] results, ImagePlus ip0, String studyID, double noiseLevel, double maxPixel, double peThold) {
		String resultFile = SAVEPATH + File.separator + "measurements_results.csv" ;

		String header = ip0.getStack().getSliceLabel(1);
		String studyDate = header.substring(header.indexOf("Study Date") + 11, header.indexOf("0008,0021") - 1);
		String patientName = header.substring(header.indexOf("Patient's Name") + 15, header.indexOf("0010,0020") - 1);
		String patientID = header.substring(header.indexOf("Patient ID") + 11, header.indexOf("Patient ID") + 20);

		String output = "";

		double  pixelSpacing = Double.parseDouble(header.substring(header.indexOf("Pixel Spacing") + 15, header.indexOf("Pixel Spacing") + 22));
		double sliceSpacing = 0.65;
		IJ.log("pixel spacing: " + pixelSpacing);
		double voxelVol = ( pixelSpacing * pixelSpacing * sliceSpacing) / 1000; //cc's

		try {
			File myDir = new File(resultFile);
			if (!myDir.exists()) {
				boolean worked = myDir.createNewFile();
				if (!worked) {
					System.err
							.println("File " + myDir + "could not be created");
					return;
				}

				output = "StudyID,Name,MRN,Study Date,Measurement Date, KPACS filepath,Noise Level,Noise Level as % of Max Pixel,"
						+ "PEthreshold, Mean PE, Mean SER,Peak PE,Peak SER,VOE2(cc),VOE3(cc),VOE4(cc),InitialRapidEnhancement%,"
						+ "InitialMediumEnhancement%,CombinedPersistVol%,CombinedPlateauVol%,CombinedwashoutVol%";
				output += "break";
			}

			Scanner console = new Scanner(myDir);

			while (console.hasNextLine()) {
				output += console.nextLine();
				output += "break";
			}

			console.close();

			PrintStream ps = new PrintStream(new BufferedOutputStream(new FileOutputStream(myDir)));
			String[] outputSplit = output.split("break");

			for (int i = 0; i < outputSplit.length; i++) {
				ps.println(outputSplit[i]);
			}

			String line = studyID + "," + patientName + "," + patientID + ","
					+ studyDate + "," + returnDate("MM/dd/yy")
					+ ",KPACS fp placeholder," + noiseLevel + ","
					+ (100 * noiseLevel / maxPixel) + "," + 100 * peThold + ","
					+ 100 * results[0] + "," + results[1] + "," + results[2] + ","
					+ results[3] + "," + voxelVol * results[4] + "," +voxelVol * results[5] + ","
					+ voxelVol * results[6] + "," + results[7] + "," + results[8] + ","
					+ results[9] + "," + results[10] + "," + results[11];

			ps.println(line);
			ps.close();

		} catch (IOException e) {
			IJ.log("IOException " + e + " occurred");
		}

		IJ.log("Results written to " + resultFile);


	}
	*/

	/* post: saves all of the pixels in the volume of interest from three timepoints to text files
	 * Parameters:
	 * 	ImagePlus[] plusSeries: the series of images stored based on their timepoint.
	 * 		 PreContrast plusSeries[0], then the three post
	 * 	int[] voi:  bounds of the volume of interest
	 * 		{xyroiBnds.x, (xyroiBnds.x + xyroiBnds.width), xyroiBnds.y, (xyroiBnds.y + xyroiBnds.height), z0, z1}
	 * 	String studyID: identify the study in the saved results file
	 */
	public void saveRawData(ImagePlus[] plusSeries, int[] voi, String studyID) {

		int numSeries = plusSeries.length;
		String header = plusSeries[0].getStack().getSliceLabel(1);
	//	String studyDate = header.substring(header.indexOf("Study Date") + 11,
		//		header.indexOf("0008,0021") - 1);
	//	String patientName = header.substring(
		//		header.indexOf("Patient's Name") + 16,
			//	header.indexOf("0010,0020") - 1);
		String patientID = header.substring(header.indexOf("Patient ID") + 12,header.indexOf("Patient ID") + 20);
		//String patientID = header.substring(header.indexOf("Patient ID") + 12,
		//		header.indexOf("0010,0021") - 1);

		double[][][][] datField = new double[numSeries][voi[1] - voi[0] + 1]
				[voi[3] - voi[2] + 1][voi[5] - voi[4] + 1];


		//double[] pixels = new double[numSeries];

		ImageStack[] stacks = new ImageStack[numSeries];

		for (int i = 0; i < numSeries; i++) {
			stacks[i] = plusSeries[i].getStack();
		}

		//int xabs = 0, yabs = 0, zabs = 0;

		// using four for loops to load dataField, then loop through them again to print?
		// why not print when loop through the first time?
		for (int z = voi[4]; z <= voi[5]; z++) {
			int zabs = z - voi[4];
			ImageProcessor[] pros = new ImageProcessor[numSeries];

			for (int i = 0; i < numSeries; i++) {
				pros[i] = stacks[i].getProcessor(z);
			}

			for (int y = voi[2]; y <= voi[3]; y++) {
				int yabs = y - voi[2];
				for (int x = voi[0]; x <= voi[1]; x++) {
					int xabs = x - voi[0];
					if (globalXYroi.contains(x, y)) {
						// unnecessary step, but keep for possible functionality
						// add ons
						for (int i = 0; i < numSeries; i++) {
							//pixels[i] = (double) (pros[i].getPixelValue(x, y));
							//datField[i][xabs][yabs][zabs] = pixels[i];

							double pixelData = (double) (pros[i].getPixelValue(x, y));
							datField[i][xabs][yabs][zabs] = pixelData;


						}
					} else {
						for (int i = 0; i < numSeries; i++) {
							datField[i][xabs][yabs][zabs] = 0;
						}
					}
				}
			}
		}
		String targDir = "J:\\Breast Imaging\\Restricted-Data\\Partridge\\Averi\\SPORE\\";
		String resDir = targDir + patientID + "_" + studyID;

		try {
			File myDir = new File(resDir);
			if (!myDir.exists()) {
				boolean worked = myDir.mkdir(); // creates file, if it doesn't
												// exist
				if (!worked) {
					System.err
							.println("File " + myDir + "could not be created");
					return;
				}

			}
		//	--- This is creating the file three times?----
			// also why not use a file array, stack, queue, etc.
			File resDirFile = new File(resDir);
			if (!resDirFile.exists()) {
				resDirFile.mkdir();
			}
			File resFile0 = new File(resDir + "\\T0.txt");
			File resFile1 = new File(resDir + "\\T1.txt");
			File resFile2 = new File(resDir + "\\T2.txt");
			File resFile3 = new File(resDir + "\\T3.txt");

			// you already created the files why check that they exist and make them again?
			if (resFile0.exists()) {
				resFile0 = new File(resDir + "\\T0.1.txt");
			}
			if (resFile1.exists()) {
				resFile1 = new File(resDir + "\\T1.1.txt");
			}
			if (resFile2.exists()) {
				resFile2 = new File(resDir + "\\T2.1.txt");
			}
			if (resFile3.exists()) {
				resFile3 = new File(resDir + "\\T3.1.txt");
			}

			// didn't you already create the file?
			resFile0.createNewFile();
			resFile1.createNewFile();
			resFile2.createNewFile();
			resFile3.createNewFile();

			// why not do this: PrintStream stream = new PrintStream(resFile0);
			PrintStream ps0 = new PrintStream(new BufferedOutputStream(
					new FileOutputStream(resFile0)));
			PrintStream ps1 = new PrintStream(new BufferedOutputStream(
					new FileOutputStream(resFile1)));
			PrintStream ps2 = new PrintStream(new BufferedOutputStream(
					new FileOutputStream(resFile2)));
			PrintStream ps3 = new PrintStream(new BufferedOutputStream(
					new FileOutputStream(resFile3)));

			//PrintStream stream = new PrintStream(resFile0);

			for (int z = voi[4]; z <= voi[5]; z++) {
				int zabs = z - voi[4];

				for (int y = voi[2]; y <= voi[3]; y++) {
					int yabs = y - voi[2];
					for (int x = voi[0]; x <= voi[1]; x++) {
						int xabs = x - voi[0];
						// if(datField0[xabs][yabs][zabs] != 0){
						ps0.println("" + datField[0][xabs][yabs][zabs]);
						ps1.println("" + datField[1][xabs][yabs][zabs]);
						ps2.println("" + datField[2][xabs][yabs][zabs]);
						if (numSeries == 4) {
							ps3.println("" + datField[3][xabs][yabs][zabs]);
						}
						// }
					}
					ps0.println("-1");
					ps1.println("-1");
					ps2.println("-1");
					ps3.println("-1");
				}
				ps0.println("-2");
				ps1.println("-2");
				ps2.println("-2");
				ps3.println("-2");
			}
			ps0.close();
			ps1.close();
			ps2.close();
			ps3.close();

			// delete last file if only 3 timepoints
			if (numSeries == 3) {
				resFile3.delete();
			}
		} catch (IOException e) {
			IJ.error("IOException " + e + " occurred");
			IJ.showMessage("IOException " + e + " occurred");
		}
		IJ.log("Results written to " + resDir);
	}

	/*post: saves the ser overlay to a folder labeled with the studyID in the filepath the user specifies
	 * default path is the folder the scans originated from + Results//studyID
	 * Warning: if there are already exists a folder with the same filePath, then previous results will be overwritten.
	 * throws an IOexception if one occurred
	 */

	//Yifan-changed SAVEPATH to SAVEPATH_i
	public void saveColorMap(ImagePlus ip, String studyID) {
		try {
			File resDir = new File(SAVEPATH_i + File.separator + studyID);

			if (!resDir.exists()){
				resDir.mkdir();
			}

			int n = ip.getNSlices();
			for (int i = 1; i <= n; i++) {
				ImagePlus ip0 = new ImagePlus("", ip.getStack().getProcessor(i));
				File temp = new File(SAVEPATH_i + File.separator + studyID + File.separator + "T" + i +".tif");
				temp.createNewFile();
				IJ.saveAsTiff(ip0, temp.getPath());
			}
			ip.close();

		} catch (IOException e) {
			IJ.error("IOException " + e + " occurred");
			IJ.showMessage("IOException " + e + " occurred");
		}

		IJ.log("Results written to " + SAVEPATH_i + File.separator + studyID);
	}

	private class DCMFileFilter implements java.io.FileFilter {
		public boolean accept(File f) {
			if (f.isDirectory() && !f.getName().equalsIgnoreCase("Results"))	// excludes the results file
				return true;
			String name = f.getName().toLowerCase();
			return name.endsWith("dcm");
		}
	}

	public String returnDate(String dateFormat) {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat(dateFormat);
		return sdf.format(cal.getTime());
	}

	// ********************************************************************
	// callback methods for imageListener
	// ********************************************************************
	public void imageClosed(ImagePlus imp) {
	}

	public void imageOpened(ImagePlus imp) {
	}

	// the one we actually use. Need to update mask when slice changes.
	public void imageUpdated(ImagePlus imp) {
		updateMask();
	}

	// ********************************************************************
	// Callback function for the dialogListener - update the mask based on the
	// DWI image.
	// ***********************************************************************
	public boolean dialogItemChanged(GenericDialog gd, java.awt.AWTEvent e) {
		String title = gd.getName();

		if (title == "peakPEDialog") {
			String slider = "" + gd.getSliders();
			int spotNumber = Integer
					.parseInt(slider.split(",")[4].split("=")[1]) - 1;
			IJ.selectWindow("Post1 Stack");
			ImagePlus ip = IJ.getImage();
			IJ.selectWindow("Field Stack");
			ImagePlus ipf = IJ.getImage();

			//String event = "" + e;
			// Set slice for the requested spot number, spot number is retrieved
			// from dialog slider.

			ip.setSlice((int) topSpotsList[spotNumber][2]);
			ip.getProcessor().setColor(100000);
			ip.getProcessor().draw(globalXYroi);
			ip.updateAndDraw();
			ipf.setSlice((int) topSpotsList[spotNumber][2] - voiGlobal[4] + 1);
			ipf.getProcessor().setColor(100000);
			ipf.getProcessor().draw(globalXYroi);
			ipf.updateAndDraw();

//
//			ip.setSlice((int) topSpotsList[spotNumber][2]);
//			ip.setOverlay(new Overlay(globalXYroi));
//			//ip.setOverlay(globalXYroi, Color.WHITE, 10, Color.TRANSLUCENT);
//			ipf.setSlice((int) topSpotsList[spotNumber][2] - voiGlobal[4] + 1);
//			ipf.setOverlay(new Overlay(globalXYroi));

			IJ.log("val = " + topSpotsList[spotNumber][3]);
			numChosen = spotNumber;
			drawSpot(ipf, (int) topSpotsList[spotNumber][0],
					(int) topSpotsList[spotNumber][1]);
			drawSpot(ip, (int) topSpotsList[spotNumber][0],
					(int) topSpotsList[spotNumber][1]);
			return true;
		} else if (title == "noiseDialog") {

			String slider = "" + gd.getSliders();
			NOISETHRESHOLD = Integer
					.parseInt(slider.split(",")[4].split("=")[1]);
			updateMask();
			return true;
		} else if (title == "peakPEDialog2D") {
			String slider = "" + gd.getSliders();
			int spotNumber = Integer
					.parseInt(slider.split(",")[4].split("=")[1]) - 1;
			IJ.selectWindow("Post1 Stack");
			ImagePlus ip = IJ.getImage();
			IJ.selectWindow("Field Stack");
			ImagePlus ipf = IJ.getImage();

			//String event = "" + e;
			// Set slice for the requested spot number, spot number is retrieved
			// from dialog slider.

			//ip.setSlice((int) topSpotsList[spotNumber][2] + 1);
			ip.setSlice(2);
			ip.getProcessor().setColor(100000);
			ip.getProcessor().draw(globalXYroi);
			ip.updateAndDraw();
			ipf.setSlice(2);
			//ipf.setSlice((int) topSpotsList[spotNumber][2] + 1);
			ipf.getProcessor().setColor(100000);
			ipf.getProcessor().draw(globalXYroi);
			ipf.updateAndDraw();

//
//			ip.setSlice((int) topSpotsList[spotNumber][2]);
//			ip.setOverlay(new Overlay(globalXYroi));
//			//ip.setOverlay(globalXYroi, Color.WHITE, 10, Color.TRANSLUCENT);
//			ipf.setSlice((int) topSpotsList[spotNumber][2] - voiGlobal[4] + 1);
//			ipf.setOverlay(new Overlay(globalXYroi));

			IJ.log("val = " + topSpotsList[spotNumber][2]);
			numChosen = spotNumber;

			int x = (int) topSpotsList[spotNumber][0];
			int y = (int) topSpotsList[spotNumber][1];
			ip.setOverlay(new Roi(x, y, 2, 2, 10), Color.green, 2, Color.green);
			ipf.setOverlay(new Roi(x, y, 2, 2, 10), Color.green, 2, Color.green);
//			drawSpot(ipf, (
//					;
//			drawSpot(ip, (int) topSpotsList[spotNumber][0],
//					(int) topSpotsList[spotNumber][1]);
			return true;
		}
		return false;
	}
	// ***********************************************************************
}
