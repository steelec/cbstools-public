package de.mpg.cbs.jist.intensity;

import edu.jhu.ece.iacl.jist.pipeline.AlgorithmRuntimeException;
import edu.jhu.ece.iacl.jist.pipeline.CalculationMonitor;
import edu.jhu.ece.iacl.jist.pipeline.ProcessingAlgorithm;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamBoolean;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamCollection;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamFloat;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamOption;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamInteger;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamVolume;
import edu.jhu.ece.iacl.jist.structures.image.ImageData;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataUByte;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataFloat;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataInt;
import edu.jhu.ece.iacl.jist.structures.image.VoxelType;

import edu.jhu.ece.iacl.jist.pipeline.DevelopmentStatus;
import edu.jhu.ece.iacl.jist.pipeline.ProcessingAlgorithm;
import edu.jhu.ece.iacl.jist.pipeline.AlgorithmInformation.AlgorithmAuthor;
import edu.jhu.ece.iacl.jist.pipeline.AlgorithmInformation.Citation;
import edu.jhu.ece.iacl.jist.pipeline.AlgorithmInformation;

import de.mpg.cbs.utilities.*;
import de.mpg.cbs.structures.*;
import de.mpg.cbs.libraries.*;
import de.mpg.cbs.methods.*;

import org.apache.commons.math3.stat.descriptive.rank.*;
import org.apache.commons.math3.util.*;

/*
 * @author Pierre-Louis Bazin
 */
public class JistIntensityMedianSquaredNoise extends ProcessingAlgorithm {

	// jist containers
	private ParamVolume inputImage;
	private ParamVolume maskImage;
	private ParamOption ngbParam;
	private ParamOption distParam;
	private ParamOption distribParam;
	private ParamBoolean skip0Param;
	
	private ParamVolume noiseImage;
	
	// parameters
	private		static final String[]	ngbTypes = {"3x3x3","5x5x5","7x7x7"};
	private		String		ngbType = "5x5x5";
	
	private		static final String[]	distTypes = {"distance", "difference"};
	private		String		distType = "distance";
	
	private		static final String[]	distribTypes = {"half-normal", "exponential","raw"};
	private		String		distribType = "half-normal";
	
	private		static final byte		RAW = 30;
	private		static final byte		EXP = 31;
	private		static final byte		HGAUSS = 32;
	
	//private static final byte[] ngbx = {+1,  0,  0, -1,  0,  0, +1,  0, +1, +1,  0, -1, -1,  0, +1, -1,  0, -1, +1, +1, +1, +1, -1, -1, -1, -1};
	//private static final byte[] ngby = { 0, +1,  0,  0, -1,  0, +1, +1,  0, -1, +1,  0, +1, -1,  0, -1, -1,  0, +1, -1, -1, +1, +1, -1, -1, +1};
	//private static final byte[] ngbz = { 0,  0, +1,  0,  0, -1,  0, +1, +1,  0, -1, +1,  0, +1, -1,  0, -1, -1, +1, -1, +1, -1, +1, -1, +1, -1};
	
	protected void createInputParameters(ParamCollection inputParams) {
		inputParams.add(inputImage = new ParamVolume("Input Image"));
		inputParams.add(maskImage = new ParamVolume("Mask Image (opt)"));
		maskImage.setMandatory(false);
		
		//inputParams.add(sampleParam = new ParamInteger("Sample size", 10, 1000000, 200));
		//inputParams.add(iterParam = new ParamInteger("Max iterations", 0, 1000000, 200));
		inputParams.add(distParam = new ParamOption("Measure", distTypes));
		distParam.setValue(distType);
		
		inputParams.add(distribParam = new ParamOption("Distribution", distribTypes));
		distribParam.setValue(distribType);
		
		inputParams.add(ngbParam = new ParamOption("Neighborhood connectivity", ngbTypes));
		ngbParam.setValue(ngbType);
		
		inputParams.add(skip0Param = new ParamBoolean("Skip zero values (if no mask)", false));
		
		//inputParams.add(ratioParam = new ParamFloat("Scaling ratio", 0.0f, 1.0f, 0.0f));
		//inputParams.add(adjustParam = new ParamBoolean("Two-level estimation", false));
		
		inputParams.setPackage("CBS Tools");
		inputParams.setCategory("Intensity.devel");
		inputParams.setLabel("Median-squared Noise Estimation");
		inputParams.setName("MedianSquaredNoise");

		AlgorithmInformation info = getAlgorithmInformation();
		info.add(new AlgorithmAuthor("Pierre-Louis Bazin", "bazin@cbs.mpg.de","http://www.cbs.mpg.de/"));
		info.setAffiliation("Max Planck Institute for Human Cognitive and Brain Sciences");
		info.setDescription("Noise estimation in images based on median sampling.");
		
		info.setVersion("3.0.7");
		info.setStatus(DevelopmentStatus.RC);
		info.setEditable(false);
	}

	@Override
	protected void createOutputParameters(ParamCollection outputParams) {
		outputParams.add(noiseImage = new ParamVolume("Noise Level Image",VoxelType.FLOAT));
		//outputParams.add(outImage = new ParamVolume("Outlier Image",VoxelType.FLOAT));
		//outputParams.add(countImage = new ParamVolume("Sampling Image",VoxelType.FLOAT));
		//outputParams.add(medianParam = new ParamFloat("Median noise"));
		
		outputParams.setName("median noise images");
		outputParams.setLabel("median noise images");
	}

	@Override
	protected void execute(CalculationMonitor monitor){
		
		// import the image data into 1D arrays
		ImageDataFloat inImg = new ImageDataFloat(inputImage.getImageData());
		float[][][] image = inImg.toArray3d();
		int nx = inImg.getRows();
		int ny = inImg.getCols();
		int nz = inImg.getSlices();
		int nxyz = nx*ny*nz;
		float rx = inImg.getHeader().getDimResolutions()[0];
		float ry = inImg.getHeader().getDimResolutions()[1];
		float rz = inImg.getHeader().getDimResolutions()[2];
		inImg = null;
		
		// basic mask for zero values
		boolean skip0 = skip0Param.getValue().booleanValue();
		boolean[][][] mask = new boolean[nx][ny][nz];
		if (maskImage.getImageData()!=null) {
			ImageDataUByte	maskImg = new ImageDataUByte(maskImage.getImageData());
			byte[][][] bytebuffer = maskImg.toArray3d();
			
			for (int x=0;x<nx;x++) for (int y=0;y<ny;y++) for (int z=0;z<nz;z++) {
				mask[x][y][z] = (bytebuffer[x][y][z]>0);
			}
		} else {
			for (int x=0;x<nx;x++) for (int y=0;y<ny;y++) for (int z=0;z<nz;z++) {
				if (image[x][y][z]!=0 || !skip0) mask[x][y][z] = true;
				else mask[x][y][z]=false;
			}
		}
		
		// main algorithm
		
		int ngb = 1; 
		int nsize = 27; 
		if (ngbParam.getValue().equals("5x5x5")) {ngb = 2; nsize = 125;}
		else if (ngbParam.getValue().equals("7x7x7")) {ngb = 3; nsize = 343;}
		System.out.println("search region distance: "+ngb+", size: "+nsize);
		
		double[] sample = new double[nsize];
		//float[] range = new float[ngb];
		
		boolean abs = false;
		if (distParam.getValue().equals("distance")) abs = true;
		
		byte distrib = RAW;
		if (distribParam.getValue().equals("half-normal")) distrib = HGAUSS;
		else if (distribParam.getValue().equals("exponential")) distrib = EXP;
		
		// 1. find the median of distances in neighborhood
		float[][][] first = new float[nx][ny][nz];
		for (int x=ngb;x<nx-ngb;x++) for (int y=ngb;y<ny-ngb;y++) for (int z=ngb;z<nz-ngb;z++) {
			if (mask[x][y][z]) {
				int nsample=0;
				for (int dx=-ngb;dx<=ngb;dx++) for (int dy=-ngb;dy<=ngb;dy++) for (int dz=-ngb;dz<=ngb;dz++) {
					if (mask[x+dx][y+dy][z+dz]) {
						if (abs) sample[nsample] = Numerics.abs(image[x][y][z]-image[x+dx][y+dy][z+dz]);
						else sample[nsample] = image[x][y][z]-image[x+dx][y+dy][z+dz];
						nsample++;
					}
				}
				// estimate mean, variance robustly
				Percentile measure = new Percentile();
				first[x][y][z] = (float)measure.evaluate(sample, 0, nsample, 50.0);
			}
		}
		
		// 2. compute the median of median distances in neighborhood
		double[] median = new double[nsize];
		float[][][] second = new float[nx][ny][nz];
		for (int x=ngb;x<nx-ngb;x++) for (int y=ngb;y<ny-ngb;y++) for (int z=ngb;z<nz-ngb;z++) {
			if (mask[x][y][z]) {
				int nmedian = 0;
				for (int dx=-ngb;dx<=ngb;dx++) for (int dy=-ngb;dy<=ngb;dy++) for (int dz=-ngb;dz<=ngb;dz++) {
					if (mask[x+dx][y+dy][z+dz]) {
						if (abs) median[nmedian] = first[x+dx][y+dy][z+dz];
						else median[nmedian] = first[x+dx][y+dy][z+dz];
						nmedian++;
					}
				}
				// estimate mean, variance robustly
				Percentile measure = new Percentile();
				second[x][y][z] = (float)measure.evaluate(median, 0, nmedian, 50.0);
				
				// estimate parameters of the underlying distribution
				if (distrib==HGAUSS) second[x][y][z] /= 0.67448975; 	// med = sigma x sqrt(2) x erf-1(1/2)
				else if (distrib==EXP) second[x][y][z] /= FastMath.log(2.0);
				
				// this is the distribution of the difference: rescale by 1/2 for the distribution of noise
				second[x][y][z] *= 0.5f;
			}
		}
		
		// output
		ImageDataFloat bufferData = new ImageDataFloat(second);
		bufferData.setHeader(inputImage.getImageData().getHeader());
		bufferData.setName(inputImage.getImageData().getName()+"_medsq");
		noiseImage.setValue(bufferData);
		bufferData = null;
		second = null;				
	}


}
