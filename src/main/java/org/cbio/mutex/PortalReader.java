package org.cbio.mutex;

import org.cbio.causality.analysis.CNVerifier;
import org.cbio.causality.analysis.Graph;
import org.cbio.causality.data.portal.*;
import org.cbio.causality.idmapping.HGNC;
import org.cbio.causality.model.Alteration;
import org.cbio.causality.model.AlterationPack;
import org.cbio.causality.model.Change;
import org.cbio.causality.util.ArrayUtil;
import org.cbio.causality.util.FileUtil;
import org.cbio.causality.util.Histogram2D;
import org.cbio.causality.util.Summary;

import java.io.*;
import java.util.*;

/**
 * Reads a dataset from cBio Portal. Uses local files as cache.
 * @author Ozgun Babur
 */
public class PortalReader
{
	PortalDataset dataset;
	CBioPortalAccessor accessor;
	ExpDataManager expMan;
	CNVerifier cnVerifier;

	// fields for local analysis
	Set<String> geneSymbols;
	String outputDir;
	String outputFileName;
	boolean useNetwork;

	public PortalReader(PortalDataset dataset) throws IOException
	{
		this.dataset = dataset;
		initForPortalAccess();
	}

	protected void initForPortalAccess() throws IOException
	{
		if (accessor == null)
		{
			accessor = getPortalAccessor(dataset);
			expMan = getExpDataMan(dataset, accessor);
			expMan.setTakeLog(true);
			cnVerifier = new CNVerifier(expMan, 0.05);
		}
	}

	public void setGeneSymbols(Set<String> geneSymbols)
	{
		this.geneSymbols = geneSymbols;
	}

	public void setUseNetwork(boolean useNetwork)
	{
		this.useNetwork = useNetwork;
	}

	public void setOutputDir(String outputDir)
	{
		this.outputDir = outputDir;
		if (!this.outputDir.endsWith(File.separator)) this.outputDir += File.separator;
	}

	public void setOutputFileName(String outputFileName)
	{
		this.outputFileName = outputFileName;
	}

	protected void prepareDataDirectory() throws IOException
	{
		System.out.println("Dataset name = " + dataset.name);

		loadDataset();

		String netOnDir = getDataDir() + "network-on" + File.separator;
		new File(netOnDir).mkdirs();
		String netOffDir = getDataDir() + "network-off" + File.separator;
		new File(netOffDir).mkdirs();
		File paramFile = new File(netOnDir + "parameters.txt");
		generateParametersFile(paramFile, true);
		paramFile = new File(netOffDir + "parameters.txt");
		generateParametersFile(paramFile, false);
	}

	private void generateParametersFile(File paramFile, boolean useNetwork) throws IOException
	{
		BufferedWriter writer = new BufferedWriter(new FileWriter(paramFile));
		writer.write("max-group-size = 5\n");
		writer.write("first-level-random-iteration = 10000\n");
		writer.write("second-level-random-iteration = 100\n");
		writer.write("data-file = ../" + outputFileName + "\n");
		writer.write("search-on-signaling-network = " + useNetwork + "\n");
		if (dataset.subtypeCases != null && dataset.subtypeCases.length > 0)
			writer.write("dataset-for-subtype = " + dataset.name + "\n");
		else if (dataset.subtypeProvider != null )
			writer.write("dataset-for-subtype = " + dataset.subtypeProvider.name + "\n");
		writer.close();
	}

	public void updateParametersFile(File paramFile) throws IOException
	{
		String content = "";
		Scanner sc = new Scanner(paramFile);
		boolean dataFileArgumentExists = false;
		while (sc.hasNextLine())
		{
			String line = sc.nextLine();
			if (line.startsWith("data-file"))
			{
				dataFileArgumentExists = true;
				break;
			}
			content += line + "\n";
		}
		if (!dataFileArgumentExists)
		{
			BufferedWriter writer = new BufferedWriter(new FileWriter(paramFile));
			writer.write(content);
			writer.write("data-file = " + outputFileName);
			writer.close();
		}
	}

	/**
	 * Separates a dataset to mostly copy number altered and to mostly mutated. This is implemented
	 * for endometrial cancer dataset.
	 */
	private void separateData() throws IOException
	{
		Map<String, GeneAlt> genes = loadDataset();

		int size = genes.values().iterator().next().size();
		boolean[] cnaMarked = new boolean[size];
		boolean[] mutMarked = new boolean[size];

		for (int i = 0; i < size; i++)
		{
			int cna = 0;
			int mut = 0;

			for (String id : genes.keySet())
			{
				GeneAlt gene = genes.get(id);
				if (gene.alterations[i] == 0) continue;
				if (gene.alterations[i] == 1 || gene.alterations[i] == 4 || gene.alterations[i] == 5) mut++;
				if (gene.alterations[i] == 2 || gene.alterations[i] == 3 || gene.alterations[i] == 4 || gene.alterations[i] == 5) cna++;
			}
			cnaMarked[i] = cna >= mut;
			mutMarked[i] = cna < mut;
		}

		separateData(genes, cnaMarked, "cna");
		separateData(genes, mutMarked, "mut");
	}

	private void separateData(Map<String, GeneAlt> genes, boolean[] select, String ext) throws IOException
	{
		String dir = getDataDir();
		dir = dir.substring(0, dir.length() - 1);
		dir += "-" + ext + File.separator;
		new File(dir + "network-on").mkdirs();
		new File(dir + "network-off").mkdirs();

		FileUtil.copyFile(getDataDir() + "network-on" + File.separator + "parameters.txt", dir + "network-on" + File.separator + "parameters.txt");
		FileUtil.copyFile(getDataDir() + "network-off" + File.separator + "parameters.txt", dir + "network-off" + File.separator + "parameters.txt");

		Scanner sc = new Scanner(new File(getTabDelimFilename()));
		String line = sc.nextLine();
		line = line.substring(line.indexOf("\t") + 1);
		String[] cases = line.split("\t");

		BufferedWriter writer = new BufferedWriter(new FileWriter(dir + "DataMatrix.txt"));
		writer.write("ID");

		for (int i = 0; i < cases.length; i++)
		{
			if (select[i]) writer.write("\t" + cases[i]);
		}

		for (String gene : genes.keySet())
		{
			writer.write("\n" + gene);
			GeneAlt alt = genes.get(gene);

			for (int i = 0; i < cases.length; i++)
			{
				if (select[i]) writer.write("\t" + alt.alterations[i]);
			}
		}

		writer.close();
	}

	public Map<String, GeneAlt> loadDataset() throws IOException
	{
		if (!new File(getTabDelimFilename()).exists())
		{
			prepareTabDelimitedFile(500);
		}
		return readTabDelimitedData(getTabDelimFilename());
	}

	public Map<String, GeneAlt> readTabDelimitedData(String filename) throws IOException
	{
		Map<String, GeneAlt> map = new HashMap<String, GeneAlt>();
		BufferedReader reader = new BufferedReader(new FileReader(filename));

		// skip header
		reader.readLine();

		for (String line = reader.readLine(); line != null; line = reader.readLine())
		{
			String[] token = line.split("\t");
			GeneAlt gene = new GeneAlt(token);
			map.put(gene.id, gene);
		}
		reader.close();
		return map;
	}

	private void keepTopAltered(Map<String, AlterationPack> map, boolean[] hyper, int limit)
	{
		final Map<String, Integer> cnt = new HashMap<String, Integer>();
		for (String gene : map.keySet())
		{
			AlterationPack pack = map.get(gene);

			Change[] ch = pack.get(Alteration.GENOMIC);
			int altCnt = 0;
			for (int i = 0; i < ch.length; i++)
			{
				if (!hyper[i] && ch[i].isAltered()) altCnt++;
			}

			cnt.put(gene, altCnt);
		}
		List<String> genes = new ArrayList<String>(map.keySet());
		Collections.sort(genes, new Comparator<String>()
		{
			@Override
			public int compare(String o1, String o2)
			{
				return cnt.get(o2).compareTo(cnt.get(o1));
			}
		});

		if (genes.size() > limit) genes = genes.subList(0, limit);
		System.out.println("Cutoff-alteration ratio = " + cnt.get(genes.get(genes.size() - 1)));

		Set<String> remove = new HashSet<String>();
		for (String gene : map.keySet())
		{
			if (!genes.contains(gene)) remove.add(gene);
		}
		for (String gene : remove)
		{
			map.remove(gene);
		}
	}

	private void cropToMutSigGistic(Set<String> symbols, Network graph)
	{
		Set<String> syms = new HashSet<String>(symbols);

		filterToMutsigAndGistic(syms, graph, dataset, 0.05);
		System.out.println("Number of highly altered genes = " + syms.size());

		Set<String> neighbors = graph.getNeighbors(syms);
		Set<String> downstream = graph.getDownstream(syms);
		Set<String> upOfDown = graph.getUpstream(downstream);

		syms.addAll(neighbors);
		syms.addAll(upOfDown);

		symbols.retainAll(syms);
		System.out.println("Proximity of highly-altered = " + syms.size());
	}

	public static void filterToMutsigAndGistic(Set<String> symbols, Graph graph, PortalDataset dataset,
		double fdrThr)
	{
		String study = dataset.caseList.substring(0, dataset.caseList.indexOf("_")).toUpperCase();
		Set<String> genes = BroadAccessor.getMutsigGenes(study, fdrThr, true);
		System.out.println("MutSig genes = " + genes.size());

		List<String> gistic = BroadAccessor.getExpressionVerifiedGistic(study, fdrThr);

		Set<String> mutsigNeigh = graph.getNeighbors(genes);
		gistic.retainAll(mutsigNeigh);
		System.out.println("Gistic at mutsig neighborhood = " + gistic.size());

		genes.addAll(gistic);
		symbols.retainAll(genes);
	}

	protected String getDataDir()
	{
		if (outputDir == null)
		{
			outputDir = "data-tcga-rerun" + File.separator + dataset.name + File.separator;
		}
		return outputDir;
	}

	protected String getTabDelimFilename()
	{
		if (outputFileName == null)
		{
			outputFileName =  "DataMatrix.txt";
		}
		return getDataDir() + outputFileName;
	}

	public void prepareTabDelimitedFile(int geneLimit) throws IOException
	{
//		Network graph = null;

		if (geneSymbols == null)
		{
//			if (useNetwork)
//			{
//				graph = new Network();
//				geneSymbols = graph.getSymbols();
//				cropToMutSigGistic(geneSymbols, graph);
//			}
//			else
				geneSymbols = HGNC.getAllSymbols();
		}

		System.out.println("Initial gene size = " + geneSymbols.size());

		Map<String, AlterationPack> altMap = readAlterationsFromPortal(geneSymbols);
		System.out.println("Genes with data = " + altMap.size());
		System.out.println("Original sample size = " + altMap.values().iterator().next().getSize());

		verifyCopyNumberWithExpression(altMap);

		boolean[] hyper = getHyperAltered(altMap);
		dataset.hyper = hyper;
		System.out.println("Hyper altered size = " + ArrayUtil.countValue(dataset.hyper, true));

		filterToAltCnt(altMap, hyper, geneLimit);
		System.out.println("After filtering out less-altered = " + altMap.size());

//		if (useNetwork)
//		{
//			if (graph == null) graph = new Network();
//			filterDisconnected(altMap, graph);
//			System.out.println("After filtering disconnected = " + altMap.size());
//		}

		CaseList caseList = accessor.getCurrentCaseList();
		String[] cases = caseList.getCases();

		String dir = getDataDir();
		if (!(new File(dir).exists())) new File(dir).mkdirs();

		BufferedWriter writer = new BufferedWriter(new FileWriter(getTabDelimFilename()));

		writer.write("ID");
		int i = 0;
		for (String c : cases)
		{
			if (!hyper[i++]) writer.write("\t" + c);
		}

		List<String> ids = new ArrayList<String>(altMap.keySet());
		Collections.sort(ids);

		for (String id : ids)
		{
			writer.write("\n" + id);

			AlterationPack pack = altMap.get(id);
			int[] alts = GeneAlt.convertAlterations(pack, hyper);

			for (int alt : alts)
			{
				writer.write("\t" + alt);
			}
		}
		writer.close();
	}

	public Map<String, AlterationPack> readAlterationsFromPortal(Collection<String> syms)
		throws IOException
	{
		initForPortalAccess();
		long time = System.currentTimeMillis();

		Map<String, AlterationPack> map = new HashMap<String, AlterationPack>();

		List<String> sorted = new ArrayList<String>(syms);
		Collections.sort(sorted);
		for (String sym : sorted)
		{
			AlterationPack alt = accessor.getAlterations(sym);
			if (alt != null && alt.get(Alteration.COPY_NUMBER) != null &&
				alt.get(Alteration.MUTATION) != null)
			{
				alt.complete(Alteration.GENOMIC);
				map.put(sym, alt);
			}
		}
		time = System.currentTimeMillis() - time;
		double seconds = time / 1000D;
		if (seconds > 5) System.out.println("read in " + seconds + " seconds");

		return map;
	}

	public void verifyCopyNumberWithExpression(Map<String, AlterationPack> altMap)
	{
		for (String id : altMap.keySet())
		{
			AlterationPack alt = altMap.get(id);

			cnVerifier.verify(alt);

			removeMinorCopyNumberAlts(alt);
			alt.complete(Alteration.GENOMIC);
		}
	}

	public void filterToAltCnt(Map<String, AlterationPack> altMap, boolean[] hyper, int geneLimit)
	{
		Set<String> remove = new HashSet<String>();
		final Map<String, Integer> cntMap = new HashMap<String, Integer>();

		for (String id : altMap.keySet())
		{
			AlterationPack alt = altMap.get(id);
			Change[] ch = alt.get(Alteration.GENOMIC);
			assert ch.length == hyper.length;

			int cnt = 0;
			for (int i = 0; i < ch.length; i++)
			{
				if (!hyper[i] && ch[i].isAltered()) cnt++;
			}

			cntMap.put(id, cnt);

			// todo don't forget here!
//			if ((cnt / (double) ch.length) < dataset.minAltThr)
			if ((cnt / (double) ch.length) < 0.01)
			{
				remove.add(id);
			}
		}
		for (String id : remove)
		{
			altMap.remove(id);
		}

		if (altMap.size() > geneLimit)
		{
			System.out.println("Genes before limit = " + altMap.size());

			List<String> sorted = new ArrayList<String>(altMap.keySet());
			Collections.sort(sorted, new Comparator<String>()
			{
				@Override
				public int compare(String o1, String o2)
				{
					return cntMap.get(o2).compareTo(cntMap.get(o1));
				}
			});

			int limitCnt = cntMap.get(sorted.get(geneLimit));
			System.out.println("limitCnt = " + limitCnt);
			remove.clear();

			for (String id : altMap.keySet())
			{
				if (cntMap.get(id) <= limitCnt) remove.add(id);
			}
			for (String id : remove)
			{
				altMap.remove(id);
			}
		}
	}

	public void removeMinorCopyNumberAlts(AlterationPack gene)
	{
		if (gene.get(Alteration.COPY_NUMBER) == null) return;

		int up = 0;
		int dw = 0;
		for (Change c : gene.get(Alteration.COPY_NUMBER))
		{
			if (c == Change.ACTIVATING) up++;
			else if (c == Change.INHIBITING) dw++;
		}

		if (up == 0 && dw == 0) return;

		if (up == dw)
		{
			System.out.println("Gene " + gene.getId() + " is equally altered (up: " + up +
				", dw: " + dw + "). Choosing none");
		}

		if (up > 0 && dw > 0)
		{
			Change c = dw < up ? Change.ACTIVATING : dw > up ? Change.INHIBITING : null;

			Change[] changes = gene.get(Alteration.COPY_NUMBER);
			for (int i = 0; i < gene.getSize(); i++)
			{
				changes[i] = changes[i] == c ? c : Change.NO_CHANGE;
			}
		}
	}

	public void filterDisconnected(Map<String, AlterationPack> altMap, Graph graph)
	{
		Set<String> remove = new HashSet<String>();
		for (String s : new HashSet<String>(altMap.keySet()))
		{
			Set<String> related = graph.getGenesWithCommonDownstream(s);
			related.remove(s);
			related.retainAll(altMap.keySet());
			if (related.isEmpty())
			{
				remove.add(s);
			}
		}

		for (String s : remove)
		{
			altMap.remove(s);
		}
	}

	public static CBioPortalAccessor getPortalAccessor(PortalDataset data) throws IOException
	{
		CBioPortalAccessor cBioPortalAccessor = new CBioPortalAccessor();

		CancerStudy cancerStudy = findCancerStudy(cBioPortalAccessor.getCancerStudies(), data.study); // GBM
		cBioPortalAccessor.setCurrentCancerStudy(cancerStudy);

		List<GeneticProfile> geneticProfilesForCurrentStudy =
			cBioPortalAccessor.getGeneticProfilesForCurrentStudy();
		List<GeneticProfile> gp = new ArrayList<GeneticProfile>();
		for (String prof : data.profile)
		{
			if (prof.endsWith("mrna")) continue;
			gp.add(findProfile(geneticProfilesForCurrentStudy, prof));
		}
		cBioPortalAccessor.setCurrentGeneticProfiles(gp);

		List<CaseList> caseLists = cBioPortalAccessor.getCaseListsForCurrentStudy();
		cBioPortalAccessor.setCurrentCaseList(findCaseList(caseLists, data.caseList));
		return cBioPortalAccessor;
	}

	private ExpDataManager getExpDataMan(PortalDataset data, CBioPortalAccessor acc) throws IOException
	{
		for (String prof : data.profile)
		{
			if (prof.endsWith("mrna"))
			{
				return new ExpDataManager(
					findProfile(acc.getGeneticProfilesForCurrentStudy(), prof),
					acc.getCurrentCaseList());
			}
		}
		return null;
	}

	private boolean[] getHyperAltered(Map<String, AlterationPack> altMap)
	{
		int size = altMap.values().iterator().next().getSize();
		double[] altSizes = new double[size];

		for (int i = 0; i < size; i++)
		{
			int cnt = 0;
			for (AlterationPack gene : altMap.values())
			{
				if (gene.get(Alteration.GENOMIC)[i].isAltered()) cnt++;
			}
			altSizes[i] = cnt;
		}

		return Summary.markOutliers(altSizes, true);
	}


	private static CancerStudy findCancerStudy(List<CancerStudy> list, String id)
	{
		for (CancerStudy study : list)
		{
			if (study.getStudyId().equals(id)) return study;
		}
		return null;
	}

	private static CaseList findCaseList(List<CaseList> list, String id)
	{
		for (CaseList cl : list)
		{
			if (cl.getId().equals(id)) return cl;
		}
		return null;
	}

	private static GeneticProfile findProfile(List<GeneticProfile> list, String id)
	{
		for (GeneticProfile profile : list)
		{
			if (profile.getId().equals(id)) return profile;
		}
		return null;
	}

	private void plotMutCNADistribution() throws IOException
	{
		Map<String, GeneAlt> genes = loadDataset();

		Histogram2D h = new Histogram2D(5);
		int size = genes.values().iterator().next().size();
		for (int i = 0; i < size; i++)
		{
			int cna = 0;
			int mut = 0;

			for (String id : genes.keySet())
			{
				GeneAlt gene = genes.get(id);
				if (gene.alterations[i] == 0) continue;
				if (gene.alterations[i] == 1 || gene.alterations[i] == 4 || gene.alterations[i] == 5) mut++;
				if (gene.alterations[i] == 2 || gene.alterations[i] == 3 || gene.alterations[i] == 4 || gene.alterations[i] == 5) cna++;
			}

//			h.count(Math.log(cna) / Math.log(2), Math.log(mut) / Math.log(2));
			h.count(cna, mut);
		}
		h.plot();
	}

	public static void main(String[] args) throws IOException
	{
		for (PortalDatasetEnum dataset : PortalDatasetEnum.values())
		{
			if (dataset.data.name.equals("simulated") || dataset.data.name.equals("sarcoma") ||
				dataset.data.name.endsWith("-pub")) continue;

			PortalReader pr = new PortalReader(dataset.data);
			pr.prepareDataDirectory();
		}

//		PortalReader pr = new PortalReader(PortalDatasetEnum.UCEC.data);
//		pr.separateData();

//		pr.plotMutCNADistribution();
	}
}
