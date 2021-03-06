package uk.co.terragaming.TerraCore.Commands;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.Text.Builder;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.inject.Injector;

import uk.co.terragaming.TerraCore.CorePlugin;
import uk.co.terragaming.TerraCore.Commands.annotations.Command;
import uk.co.terragaming.TerraCore.Commands.arguments.ArgumentParser;
import uk.co.terragaming.TerraCore.Commands.arguments.ObjectArgument;
import uk.co.terragaming.TerraCore.Commands.exceptions.ArgumentException;
import uk.co.terragaming.TerraCore.Commands.exceptions.ArgumentException.NotEnoughArgumentsException;
import uk.co.terragaming.TerraCore.Commands.exceptions.ArgumentException.TooManyArgumentsException;
import uk.co.terragaming.TerraCore.Util.Text.MyText;

public class CommandHandler implements MethodCommandService {
	
	@Inject
	Injector injector;
	
	private static final ObjectArgument DEFAULT_ARG_PARSER = new ObjectArgument();
	
	private final CorePlugin plugin;
	
	private List<Object> handlers = Lists.newArrayList();
	private SetMultimap<String, MethodCommand> commands;
	private Set<String> rootLabels;
	
	private List<ArgumentParser> argumentParsers;
	
	public CommandHandler(CorePlugin plugin){
		checkNotNull(plugin);
		this.plugin = plugin;
		
		commands = MultimapBuilder.hashKeys().hashSetValues().build();
		rootLabels = new HashSet<String>();
		
		argumentParsers = Lists.newArrayList();
	}
	
	public MethodCommand getCommand(String path){
		return (MethodCommand) commands.get(path).toArray()[0];
	}
	
	public boolean hasCommand(String parentPath) {
		return commands.containsKey(parentPath);
	}
	
	@Listener
	public void onServerStart(GameStartedServerEvent event){
		for (Object handler : handlers){
			injector.injectMembers(handler);
		}
	}
	
	@Override
	public void registerCommands(Object plugin, Object handler) {
		Plugin p = plugin.getClass().getAnnotation(Plugin.class);
		checkNotNull(p, "plugin");
		
		List<Method> methods = Lists.newArrayList(handler.getClass().getMethods());

		methods.sort((Method m1, Method m2) -> {
			if (!m1.isAnnotationPresent(Command.class)) return 0;
			if (!m2.isAnnotationPresent(Command.class)) return 0;
			
			return m1.getAnnotation(Command.class).value().split(" ").length - m2.getAnnotation(Command.class).value().split(" ").length;
		});
		
		boolean first = true;
		for (Method m : methods){
			if (first){
				handlers.add(handler);
				first = false;
			}
			if (!m.isAnnotationPresent(Command.class)) continue;
			MethodCommand command = new MethodCommand(handler, m, this);
			
			if (CorePlugin.isDevelopmentMode()){
				getLogger().info("Registered Command '<h>" + (MyText.implode(command.getPath(), " ")) + "<r>'");
			}
						
			for (String path : command.getAliasPaths()){
				commands.put(path, command);
			}
			
			for (String alias : command.getAliases()){			
				if (!command.hasParent() && !rootLabels.contains(alias)){
					Sponge.getCommandManager().register(plugin, new SpongeCommandImpl(alias, this), alias);
					rootLabels.add(alias);
				}
			}
		}
	
	}
	
	@Override
	public void addArgumentParser(Object plugin, ArgumentParser parser) {
		checkNotNull(plugin, "plugin");
		Plugin p = plugin.getClass().getAnnotation(Plugin.class);
		checkNotNull(p, "plugin");
		
		if (argumentParsers == null){
			argumentParsers = Lists.newArrayList();
		}
		
		argumentParsers.add(parser);
	}
	
	@Override
	public ArgumentParser getArgumentParser(Class<?> type) {
		for (int i = argumentParsers.size() - 1; i >= 0; i--){
			ArgumentParser ap = argumentParsers.get(i);
			if (ap.isTypeSupported(type)) return ap;
		}
		
		return DEFAULT_ARG_PARSER;
	}


	public CommandResult processCommand(CommandSource source, String command) {
		List<ArgumentException> exceptions = Lists.newArrayList();
		
		
		// try to execute the exact matches
		for (String label : getExactMatches(command)){
			for (MethodCommand mc : commands.get(label)){
				try{
					return mc.execute(source, command.substring(label.length()).trim());
				} catch (ArgumentException ex){
					exceptions.add(ex);
				} catch (NumberFormatException ex){}
			}
		}
		
		// If no command could execute successfully:
		Set<String> cmdSet = getPossibleMatches(command, true);
		List<Text> usages = Lists.newArrayList();
		Set<MethodCommand> done = Sets.newHashSet();
		
		for (String label : cmdSet){
			for (MethodCommand mc : commands.get(label)){
				if (done.contains(mc)) continue;
				done.add(mc);
				usages.add(mc.getUsage(source));
			}
		}
		
		if (exceptions.isEmpty()){
			if (!usages.isEmpty()){
				source.sendMessage(Text.of(TextColors.RED, "That command could not be found. Maybe you ment:"));
				source.sendMessages(usages);
			} else {
				source.sendMessage(Text
					.builder("That command could not be found! Try '")
					.color(TextColors.RED)
					.append(Text
							.builder("/help")
							.color(TextColors.DARK_GRAY)
							.onClick(TextActions.suggestCommand("/help"))
							.build()
					)
					.append(Text.builder("' if you need some!").color(TextColors.RED).build())
					.build());
			}
		} else {
			
			// sort errors
			Map<String, List<ArgumentException>> invalidArgs = Maps.newHashMap();
			Map<String, NotEnoughArgumentsException> notEnoughArgs = Maps.newHashMap();
			
			TooManyArgumentsException tooManyArgs = null;
			
			for (ArgumentException ex : exceptions){
				while (ex != null){
					
					if (ex instanceof TooManyArgumentsException) tooManyArgs = (TooManyArgumentsException) ex;
					else if (ex instanceof NotEnoughArgumentsException) notEnoughArgs.put(ex.getExpectedType().getName(), (NotEnoughArgumentsException) ex);
					else {
						
						List<ArgumentException> exs = invalidArgs.get(ex.getWrongArgument());
						if (exs == null){
							exs = Lists.newArrayList();
							invalidArgs.put(ex.getWrongArgument(), exs);
						}
						
						exs.add(ex);
					}
					
					ex = ex.getAnother();
				}
			}
			
			// create error description
			Builder tb = Text.builder();
			tb.append(Text.of("\n"));
			
			if (tooManyArgs != null){
				tb.append(tooManyArgs.getDescription());
				if (!notEnoughArgs.isEmpty() || !invalidArgs.isEmpty()) tb.append(Text.of("\n", TextColors.WHITE, "or "));
			}
			
			if (!notEnoughArgs.isEmpty()){
				tb.append(notEnoughArgs.values().iterator().next().getDescription(), Text.of(" "));
				if (notEnoughArgs.size() > 1){
					tb.append(Text.of(TextColors.RED, "Expected one of: "));
				} else {
					tb.append(Text.of(TextColors.RED, "Expected Type: "));
				}
				
				boolean first = true;
				for (NotEnoughArgumentsException ex : notEnoughArgs.values()){
					if (first) first = false;
					else tb.append(Text.of(TextColors.WHITE, ", "));
					ArgumentParser ap = ex.getParser();
					if (ap != null) tb.append(Text.of(TextColors.GRAY, ap.getArgumentTypeName(ex.getExpectedType())));
				}
				if (!invalidArgs.isEmpty()) tb.append(Text.of("\n", TextColors.WHITE, "or "));
			}
			
			if (!invalidArgs.isEmpty()){
				boolean firstArg = true;
				for (List<ArgumentException> exList : invalidArgs.values()){
					if (firstArg) firstArg = false;
					else tb.append(Text.of("\n", TextColors.WHITE, "or "));
					
					if (exList.size() > 1){
						tb.append(Text.of(TextColors.RED, "Invalid Argument: '", TextColors.WHITE, exList.get(0).getWrongArgument(), TextColors.RED, "'"), Text.of(" "), Text.of(TextColors.RED, "matches none of: "));
						
						boolean first = true;
						for (ArgumentException ex : exList){
							if (first) first = false;
							else tb.append(Text.of(TextColors.WHITE, ", "));
							
							ArgumentParser ap = ex.getParser();
							if (ap != null) tb.append(Text.of(TextColors.GRAY, ap.getArgumentTypeName(ex.getExpectedType())));
						}
					} else {
						tb.append(exList.get(0).getDescription());
					}
				}
			}
			
			tb.append(Text.of("\n"));
			if (!usages.isEmpty()){
				tb.append(Text.joinWith(Text.of("\n"), usages.toArray(new Text[usages.size()])));
			} else {
				tb.append(exceptions.get(0).getDescription(), Text.of("\n"));
				tb.append(Text.of(TextColors.RED, "Try '", 
					Text.builder("/help")
						.color(TextColors.DARK_GRAY)
						.onClick(TextActions.suggestCommand("/help"))
						.build()
					, TextColors.RED, "' if you need some."));
			}
			
			source.sendMessage(tb.build());
		}
		
		return CommandResult.empty();
		
	}

	public Collection<? extends String> getCommandSuggestions(CommandSource source, String command) {
		Set<String> suggestions = Sets.newHashSet();
		Set<String> cmdSet = getPossibleMatches(command, true);
		
		if (!cmdSet.isEmpty()){
			for (String label : cmdSet){
				for (MethodCommand mc : commands.get(label)){
					boolean p = false;
					for (String perm : mc.getPerms()){
						if (source.hasPermission(perm)) p = true;
					}
					if (!p) continue;
					
					if (command.startsWith(label + " ")){
						suggestions.addAll(mc.getSuggestions(source, command.substring(label.length() + 1)));
					} else if (label.startsWith(command)){
						String[] sublabels = label.split(" ");
						String[] words = command.split(" ");
						
						if (command.endsWith(" ")){
							if (sublabels.length > words.length) suggestions.add(sublabels[words.length]);
						} else {
							if (sublabels.length > words.length - 1) suggestions.add(sublabels[words.length-1]);
						}
					}
				}
			}
		}
		return suggestions;
	}
	
	public Text getCommandUsage(CommandSource source, String command) {
		Builder usage = Text.builder();
		
		Set<String> cmdSet = getPossibleMatches(command, true);
		if (!cmdSet.isEmpty()){
			boolean isFirst = true;
			for (String label : cmdSet){
				for (MethodCommand mc : commands.get(label)){
					if (mc == null) continue;
					boolean p = false;
					for (String perm : mc.getPerms()){
						if (source.hasPermission(perm)) p = true;
					}
					if (!p) continue;
					
					if (isFirst) isFirst = false;
					else usage.append(Text.of(TextColors.GRAY, " or "));
					usage.append(Text.of(TextColors.WHITE, mc.getUsage(source)));
				}
			}
		}
		
		return usage.build();
	}

	public boolean anyPermission(CommandSource source, String command) {
		Set<String> cmdSet = getPossibleMatches(command, true);
		
		if (!cmdSet.isEmpty()){
			for (String label : cmdSet){
				for (MethodCommand mc : commands.get(label)){
					if (mc == null || mc.equals(null)) continue;
					if (mc.getPerms() == null) continue;
					for (String perm : mc.getPerms()){
						if (source.hasPermission(perm)) return true;
					}
				}
			}
		}
		
		return false;
	}
	
	private List<String> getExactMatches(String command) {
		List<String> exactMatches = new ArrayList<String>();
		
		for (String label : commands.keySet()){
			String common = Strings.commonPrefix(command, label + " ");
			
			//if an exact match is found, add to exactList
			if (common.length() == label.length() + 1 || command.equals(label)){
				exactMatches.add(label);
			}
		}
		
		//sort exactMatches from high to low
		Collections.sort(exactMatches, (String label1, String label2) -> {
			return label1.length() - label2.length();
		});
		
		return exactMatches;
	}

	private Set<String> getPossibleMatches(String command, boolean includeExternalCommands) {
		int bestMatchLength = 1;
		List<String> bestMatches = new ArrayList<String>();
		List<String> exactMatches = new ArrayList<String>();
		
		Set<String> allCommands = Sets.newHashSet();
		allCommands.addAll(commands.keySet());
		if (includeExternalCommands) allCommands.addAll(Sponge.getCommandManager().getAliases());

		for (String label : allCommands){
			String common = Strings.commonPrefix(command, label + " ");
			
			// update all best command matches
			if (common.length() > bestMatchLength) bestMatches.clear();
			if (common.length() >= bestMatchLength){
				bestMatchLength = common.length();
				bestMatches.add(label);
			}
			
			//if an exact match is found, add to eaxtList
			if (common.length() == label.length() + 1 || command.equals(label)){
				exactMatches.add(label);
			}
		}
		
		//Sorts exactMatches from High to low
		Collections.sort(exactMatches, (String label1, String label2) -> { return label1.length() - label2.length(); } );
		
		Set<String> cmdSet = new HashSet<String>();
		cmdSet.addAll(exactMatches);
		cmdSet.addAll(bestMatches);
		
		return cmdSet;
	}
	
	public CorePlugin getPlugin(){
		return plugin;
	}

	public Logger getLogger() {
		return plugin.logger;
	}
}
